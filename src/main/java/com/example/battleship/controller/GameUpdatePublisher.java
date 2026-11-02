package com.example.battleship.controller;

import com.example.battleship.config.WebSocketConfig;
import com.example.battleship.dto.ErrorResponse;
import com.example.battleship.dto.GameViewMapper;
import com.example.battleship.model.Game;
import com.example.battleship.model.Player;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/** Pushes a personalised view of the game to every human player over STOMP. */
@Component
public class GameUpdatePublisher {

    private final SimpMessagingTemplate messagingTemplate;

    public GameUpdatePublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    public void publish(Game game) {
        for (Player player : game.getPlayers()) {
            if (!player.isAi()) {
                messagingTemplate.convertAndSend(WebSocketConfig.playerTopic(game.getId(), player.getId()),
                        GameViewMapper.toView(game, player.getId()));
            }
        }
    }

    public void publishError(String gameId, String playerId, String message) {
        messagingTemplate.convertAndSend(WebSocketConfig.playerErrorTopic(gameId, playerId),
                ErrorResponse.of(400, "Bad Request", message));
    }
}
