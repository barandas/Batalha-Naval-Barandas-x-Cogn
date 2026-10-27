package com.example.battleship.dto;

import com.example.battleship.model.Difficulty;
import com.example.battleship.model.GameMode;
import com.example.battleship.model.GameState;
import java.util.List;

/** Game state as seen by one specific player: the opponent's afloat ships are never exposed. */
public record GameView(
        String gameId,
        String roomCode,
        GameMode mode,
        Difficulty difficulty,
        GameState state,
        int round,
        String playerId,
        String playerName,
        String opponentName,
        boolean yourTurn,
        boolean youReady,
        boolean opponentReady,
        Boolean youWon,
        boolean rematchRequestedByYou,
        boolean rematchRequestedByOpponent,
        CellState[][] ownBoard,
        CellState[][] enemyBoard,
        List<ShipStatusView> yourFleet,
        List<ShipStatusView> enemyFleet,
        LastShotView lastShot,
        List<String> events) {
}
