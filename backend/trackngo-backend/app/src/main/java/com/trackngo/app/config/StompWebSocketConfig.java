package com.trackngo.app.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Configures a STOMP message broker over SockJS so the mobile
 * front-end ({@code @stomp/stompjs} + {@code sockjs-client}) can
 * subscribe to real-time chat events.
 */
@Configuration
@EnableWebSocketMessageBroker
public class StompWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    // Skipped when a request has no Origin header, which covers SockJS's XHR
    // fallbacks from the mobile apps. Its WebSocket transport does carry one:
    // React Native on Android sets Origin to the server's own https host, which
    // passes only because server.forward-headers-strategy lets Tomcat see the
    // https scheme the proxy received.
    @Value("${trackngo.cors.allowed-origins}")
    private String allowedOriginsProperty;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/chat")
                .setAllowedOriginPatterns(CorsOrigins.parse(allowedOriginsProperty))
                .withSockJS();
    }
}