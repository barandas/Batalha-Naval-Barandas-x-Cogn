package com.example.battleship.controller;

import com.example.battleship.dto.ShotRequest;
import com.example.battleship.model.Board;
import com.example.battleship.model.Coordinate;
import com.example.battleship.model.InvalidMoveException;
import com.example.battleship.service.GameNotFoundException;
import com.example.battleship.service.GameService;
import com.example.battleship.controller.PlayerChannelInterceptor.SessionPlayer;
import java.util.Map;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

/**
 * STOMP endpoints. Clients send to {@code /app/games/{gameId}/shot} or {@code /app/games/{gameId}/rematch}
 * and receive their personalised view on {@code /topic/games/{gameId}/players/{playerId}}. The acting player is
 * the one the session authenticated as (see {@link PlayerChannelInterceptor}), not a player id in the payload.
 */
@Controller
public class GameWebSocketController {

    private final GameService gameService;
    private final GameUpdatePublisher publisher;

    public GameWebSocketController(GameService gameService, GameUpdatePublisher publisher) {
        this.gameService = gameService;
        this.publisher = publisher;
    }

    @MessageMapping("/games/{gameId}/shot")
    public void fire(@DestinationVariable String gameId, @Payload ShotRequest request,
                     SimpMessageHeaderAccessor headers) {
        String playerId = playerId(headers);
        if (request == null || playerId == null) {
            return;
        }
        handle(gameId, playerId, () -> {
            if (request.row() == null || request.col() == null
                    || !Coordinate.of(request.row(), request.col()).isWithinBoard()) {
                throw new InvalidMoveException("Coordenada inválida: use linhas e colunas de 0 a " + (Board.SIZE - 1) + ".");
            }
            gameService.fire(gameId, playerId, Coordinate.of(request.row(), request.col()));
        });
    }

    @MessageMapping("/games/{gameId}/rematch")
    public void rematch(@DestinationVariable String gameId, SimpMessageHeaderAccessor headers) {
        String playerId = playerId(headers);
        if (playerId == null) {
            return;
        }
        handle(gameId, playerId, () -> gameService.requestRematch(gameId, playerId));
    }

    private static String playerId(SimpMessageHeaderAccessor headers) {
        Map<String, Object> attributes = headers.getSessionAttributes();
        SessionPlayer player = PlayerChannelInterceptor.sessionPlayer(attributes);
        return player == null ? null : player.playerId();
    }

    private void handle(String gameId, String playerId, Runnable action) {
        try {
            action.run();
            publisher.publish(gameService.getGame(gameId));
        } catch (InvalidMoveException | GameNotFoundException e) {
            publisher.publishError(gameId, playerId, e.getMessage());
        }
    }
}
