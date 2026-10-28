package com.example.battleship.controller;

import com.example.battleship.dto.PlayerRequest;
import com.example.battleship.dto.ShotRequest;
import com.example.battleship.model.Board;
import com.example.battleship.model.Coordinate;
import com.example.battleship.model.InvalidMoveException;
import com.example.battleship.service.GameNotFoundException;
import com.example.battleship.service.GameService;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;

/**
 * STOMP endpoints. Clients send to {@code /app/games/{gameId}/shot} or {@code /app/games/{gameId}/rematch}
 * and receive their personalised view on {@code /topic/games/{gameId}/players/{playerId}}.
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
    public void fire(@DestinationVariable String gameId, @Payload ShotRequest request) {
        if (request == null || request.playerId() == null) {
            return;
        }
        handle(gameId, request.playerId(), () -> {
            if (request.row() == null || request.col() == null
                    || !Coordinate.of(request.row(), request.col()).isWithinBoard()) {
                throw new InvalidMoveException("Coordenada inválida: use linhas e colunas de 0 a " + (Board.SIZE - 1) + ".");
            }
            gameService.fire(gameId, request.playerId(), Coordinate.of(request.row(), request.col()));
        });
    }

    @MessageMapping("/games/{gameId}/rematch")
    public void rematch(@DestinationVariable String gameId, @Payload PlayerRequest request) {
        if (request == null || request.playerId() == null) {
            return;
        }
        handle(gameId, request.playerId(), () -> gameService.requestRematch(gameId, request.playerId()));
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
