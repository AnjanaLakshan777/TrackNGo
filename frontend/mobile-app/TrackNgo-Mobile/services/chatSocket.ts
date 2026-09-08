import { Client, IMessage, StompSubscription } from "@stomp/stompjs";
import SockJS from "sockjs-client";
import { API_BASE_URL, SOCKJS_ENDPOINT, STOMP_APP_PREFIX, STOMP_TOPIC_PREFIX } from "../config/env";
import type {
  ChatMessage,
  MessageDeleteEvent,
  MessageStatusUpdate,
  PresenceUpdate,
  TypingIndicator
} from "../types/chat";

type Unsubscribe = () => void;

export class ChatSocketClient {
  private client: Client;
  private isConnected = false;
  private pendingSubscriptions: Array<() => void> = [];
  private presenceUserId: number | null = null;
  private presenceSubscription: StompSubscription | null = null;
  private presenceListeners = new Set<(presence: PresenceUpdate) => void>();
  private connectionRefs = 0;
  private disconnectTimer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    this.client = new Client({
      webSocketFactory: () => new SockJS(`${API_BASE_URL}${SOCKJS_ENDPOINT}`),
      reconnectDelay: 1500
    });

    this.client.onConnect = () => {
      this.isConnected = true;
      this.ensurePresenceSubscription();
      this.publishPresence(true);
      this.pendingSubscriptions.forEach((subscribe) => subscribe());
      this.pendingSubscriptions = [];
    };

    this.client.onDisconnect = () => {
      this.isConnected = false;
      this.presenceSubscription = null;
    };

    /*
      onDisconnect only fires for a graceful STOMP DISCONNECT, where the broker
      acknowledges the shutdown. A socket that simply drops - the backend
      restarting, the phone changing network, the OS suspending a backgrounded
      app - never produces that acknowledgement, so without this handler
      isConnected stays true while the STOMP connection is already gone. The next
      subscribe then fails with "There is no underlying STOMP connection".

      The presence subscription is dropped as well, because it belongs to the
      socket that just died; onConnect re-creates it, along with any queued
      conversation subscriptions, on the reconnect stompjs performs by itself.
    */
    this.client.onWebSocketClose = () => {
      this.isConnected = false;
      this.presenceSubscription = null;
    };
  }

  connect(userId?: number) {
    if (userId) {
      if (
        this.presenceUserId &&
        this.presenceUserId !== userId &&
        this.isLive()
      ) {
        this.publishPresenceFor(this.presenceUserId, false);
      }
      this.presenceUserId = userId;
    }
    this.connectionRefs += 1;
    if (this.disconnectTimer) {
      clearTimeout(this.disconnectTimer);
      this.disconnectTimer = null;
    }
    if (!this.client.active) {
      this.client.activate();
    } else if (this.isLive()) {
      this.ensurePresenceSubscription();
      this.publishPresence(true);
    }
  }

  disconnect() {
    this.connectionRefs = Math.max(0, this.connectionRefs - 1);
    if (this.connectionRefs > 0) {
      return;
    }
    if (this.disconnectTimer) {
      clearTimeout(this.disconnectTimer);
    }
    this.disconnectTimer = setTimeout(() => {
      if (this.connectionRefs > 0) {
        return;
      }
      this.disconnectTimer = null;
      this.disconnectNow();
    }, 800);
  }

  private disconnectNow() {
    if (this.client.active) {
      this.publishPresence(false);
      this.safeUnsubscribe(this.presenceSubscription);
      this.presenceSubscription = null;
      this.client.deactivate();
    }
  }

  publishMessage(payload: ChatMessage) {
    if (!this.isLive()) {
      return;
    }
    this.client.publish({
      destination: `${STOMP_APP_PREFIX}/sendMessage`,
      body: JSON.stringify(payload)
    });
  }

  publishTyping(payload: TypingIndicator) {
    if (!this.isLive()) {
      return;
    }
    this.client.publish({
      destination: `${STOMP_APP_PREFIX}/typing`,
      body: JSON.stringify(payload)
    });
  }

  subscribePresence(handler: (presence: PresenceUpdate) => void): Unsubscribe {
    this.presenceListeners.add(handler);
    if (this.isLive()) {
      this.ensurePresenceSubscription();
    }

    return () => {
      this.presenceListeners.delete(handler);
    };
  }

  subscribeConversation(
    conversationId: number,
    handlers: {
      onMessage: (message: ChatMessage) => void;
      onTyping: (typing: TypingIndicator) => void;
      onStatus: (status: MessageStatusUpdate[]) => void;
      onDeleted: (event: MessageDeleteEvent) => void;
    }
  ): Unsubscribe {
    const subscriptions: StompSubscription[] = [];
    let closed = false;

    const subscribeNow = () => {
      if (closed) {
        return;
      }
      subscriptions.push(
        this.subscribeJson(
          `${STOMP_TOPIC_PREFIX}/conversations/${conversationId}`,
          handlers.onMessage
        )
      );
      subscriptions.push(
        this.subscribeJson(
          `${STOMP_TOPIC_PREFIX}/conversations/${conversationId}/typing`,
          handlers.onTyping
        )
      );
      subscriptions.push(
        this.subscribeJson(
          `${STOMP_TOPIC_PREFIX}/conversations/${conversationId}/status`,
          handlers.onStatus
        )
      );
      subscriptions.push(
        this.subscribeJson(
          `${STOMP_TOPIC_PREFIX}/conversations/${conversationId}/deleted`,
          handlers.onDeleted
        )
      );
    };

    if (this.isLive()) {
      subscribeNow();
    } else {
      this.pendingSubscriptions.push(subscribeNow);
    }

    return () => {
      closed = true;
      subscriptions.forEach((sub) => this.safeUnsubscribe(sub));
    };
  }

  private subscribeJson<T>(
    destination: string,
    callback: (payload: T) => void
  ): StompSubscription {
    return this.client.subscribe(destination, (frame: IMessage) => {
      callback(JSON.parse(frame.body) as T);
    });
  }

  private ensurePresenceSubscription() {
    if (this.presenceSubscription || !this.isLive()) {
      return;
    }

    this.presenceSubscription = this.subscribeJson<PresenceUpdate>(
      `${STOMP_TOPIC_PREFIX}/presence`,
      (presence) => {
        this.presenceListeners.forEach((listener) => listener(presence));
      }
    );
  }

  private publishPresence(online: boolean) {
    if (!this.presenceUserId) {
      return;
    }
    this.publishPresenceFor(this.presenceUserId, online);
  }

  private publishPresenceFor(userId: number, online: boolean) {
    if (!this.isLive()) {
      return;
    }

    this.client.publish({
      destination: `${STOMP_APP_PREFIX}/presence`,
      body: JSON.stringify({
        userId,
        online
      })
    });
  }

  private isLive() {
    return this.isConnected && this.client.connected;
  }

  /*
    Unsubscribing sends an UNSUBSCRIBE frame, so it needs a live connection and
    throws the same "no underlying STOMP connection" error without one. A screen
    unmounting after the socket already dropped is completely normal - the
    subscription died with the socket, so there is nothing left to cancel.
  */
  private safeUnsubscribe(subscription: { unsubscribe: () => void } | null | undefined) {
    if (!subscription || !this.isLive()) {
      return;
    }
    try {
      subscription.unsubscribe();
    } catch {
      // The connection dropped between the check above and this call.
    }
  }
}

export const chatSocket = new ChatSocketClient();
