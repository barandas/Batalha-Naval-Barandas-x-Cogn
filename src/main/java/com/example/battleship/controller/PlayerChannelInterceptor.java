package com.example.battleship.controller;

import com.example.battleship.config.WebSocketConfig;
import com.example.battleship.service.GameService;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * Binds each STOMP session to one player. The CONNECT frame must carry the {@code gameId} and {@code playerId}
 * headers of a player of that game; afterwards the session may only subscribe to that player's own topics and send
 * actions to that game. A connection can therefore neither read another player's board nor count as their presence.
 */
@Component
public class PlayerChannelInterceptor implements ChannelInterceptor {

    public static final String GAME_ID_HEADER = "gameId";
    public static final String PLAYER_ID_HEADER = "playerId";
    static final String SESSION_PLAYER = "battleship.player";

    private static final Pattern GAME_DESTINATION =
            Pattern.compile("^" + Pattern.quote(WebSocketConfig.APP_PREFIX) + "/games/([^/]+)/[^/]+$");

    /** The player a STOMP session is authenticated as. */
    public record SessionPlayer(String gameId, String playerId) {
    }

    private final GameService gameService;

    public PlayerChannelInterceptor(GameService gameService) {
        this.gameService = gameService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (command == StompCommand.CONNECT) {
            authenticate(accessor);
        } else if (command == StompCommand.SUBSCRIBE) {
            SessionPlayer player = requirePlayer(accessor);
            String destination = accessor.getDestination();
            String own = WebSocketConfig.playerTopic(player.gameId(), player.playerId());
            if (!own.equals(destination) && !WebSocketConfig.playerErrorTopic(player.gameId(), player.playerId())
                    .equals(destination)) {
                throw new MessageDeliveryException("Inscrição não permitida: " + destination);
            }
        } else if (command == StompCommand.SEND) {
            SessionPlayer player = requirePlayer(accessor);
            Matcher matcher = GAME_DESTINATION.matcher(String.valueOf(accessor.getDestination()));
            if (!matcher.matches() || !matcher.group(1).equals(player.gameId())) {
                throw new MessageDeliveryException("Envio não permitido: " + accessor.getDestination());
            }
        }
        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String gameId = accessor.getFirstNativeHeader(GAME_ID_HEADER);
        String playerId = accessor.getFirstNativeHeader(PLAYER_ID_HEADER);
        Map<String, Object> attributes = accessor.getSessionAttributes();
        if (gameId == null || playerId == null || attributes == null || !gameService.isHumanPlayer(gameId, playerId)) {
            throw new MessageDeliveryException("Jogador não pertence a esta partida.");
        }
        attributes.put(SESSION_PLAYER, new SessionPlayer(gameId, playerId));
    }

    private static SessionPlayer requirePlayer(StompHeaderAccessor accessor) {
        SessionPlayer player = sessionPlayer(accessor.getSessionAttributes());
        if (player == null) {
            throw new MessageDeliveryException("Sessão sem jogador autenticado.");
        }
        return player;
    }

    static SessionPlayer sessionPlayer(Map<String, Object> sessionAttributes) {
        return sessionAttributes == null ? null : (SessionPlayer) sessionAttributes.get(SESSION_PLAYER);
    }
}
