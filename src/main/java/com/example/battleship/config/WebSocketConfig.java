package com.example.battleship.config;

import com.example.battleship.controller.PlayerChannelInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    public static final String ENDPOINT = "/ws";
    public static final String APP_PREFIX = "/app";
    public static final String TOPIC_PREFIX = "/topic";

    private final PlayerChannelInterceptor playerChannelInterceptor;

    public WebSocketConfig(PlayerChannelInterceptor playerChannelInterceptor) {
        this.playerChannelInterceptor = playerChannelInterceptor;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(ENDPOINT).setAllowedOriginPatterns("*");
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker(TOPIC_PREFIX);
        registry.setApplicationDestinationPrefixes(APP_PREFIX);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(playerChannelInterceptor);
    }

    public static String playerTopic(String gameId, String playerId) {
        return TOPIC_PREFIX + "/games/" + gameId + "/players/" + playerId;
    }

    public static String playerErrorTopic(String gameId, String playerId) {
        return playerTopic(gameId, playerId) + "/errors";
    }
}
