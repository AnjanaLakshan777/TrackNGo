
package com.trackngo.app.config;

import com.trackngo.chat.internal.websocket.ChatWebSocketHandler;
import com.trackngo.tracking.internal.websocket.TrackingWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {
    private final ChatWebSocketHandler chatHandler;
    private final TrackingWebSocketHandler trackingHandler;

    // Applied to any handshake whose Origin isn't this server's own. Browsers
    // always send Origin, and so does React Native on Android: it defaults the
    // header to the socket URL's scheme and host. That default is same-origin,
    // so the apps pass only because server.forward-headers-strategy lets Tomcat
    // see the https scheme the proxy received.
    @Value("${trackngo.cors.allowed-origins}")
    private String allowedOriginsProperty;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        String[] origins = CorsOrigins.parse(allowedOriginsProperty);
        registry.addHandler(chatHandler, "/ws/chat").setAllowedOriginPatterns(origins);
        registry.addHandler(trackingHandler, "/ws/tracking").setAllowedOriginPatterns(origins);
    }
}

