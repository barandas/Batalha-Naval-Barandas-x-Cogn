package com.example.battleship.dto;

public record JoinResponse(String gameId, String roomCode, String playerId, GameView game) {
}
