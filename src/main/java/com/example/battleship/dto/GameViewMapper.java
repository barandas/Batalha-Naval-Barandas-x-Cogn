package com.example.battleship.dto;

import com.example.battleship.model.Board;
import com.example.battleship.model.Coordinate;
import com.example.battleship.model.Game;
import com.example.battleship.model.GameState;
import com.example.battleship.model.LastShot;
import com.example.battleship.model.Player;
import com.example.battleship.model.Ship;
import com.example.battleship.model.ShipType;
import com.example.battleship.model.ShotResult;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public final class GameViewMapper {

    private GameViewMapper() {
    }

    public static GameView toView(Game game, String playerId) {
        synchronized (game) {
            return buildView(game, playerId);
        }
    }

    private static GameView buildView(Game game, String playerId) {
        Player me = game.getPlayer(playerId);
        Optional<Player> opponent = game.findOpponent(playerId);
        boolean finished = game.getState() == GameState.FINISHED;
        LastShot lastShot = game.getLastShot();

        return new GameView(
                game.getId(),
                game.getRoomCode(),
                game.getMode(),
                game.getDifficulty(),
                game.getState(),
                game.getRound(),
                me.getId(),
                me.getName(),
                opponent.map(Player::getName).orElse(null),
                me.getId().equals(game.getCurrentTurnPlayerId()),
                me.isReady(),
                opponent.map(Player::isReady).orElse(false),
                finished ? me.getId().equals(game.getWinnerId()) : null,
                me.isRematchRequested(),
                opponent.map(p -> !p.isAi() && p.isRematchRequested()).orElse(false),
                renderBoard(me.getBoard(), true),
                opponent.map(p -> renderBoard(p.getBoard(), finished)).orElseGet(GameViewMapper::emptyGrid),
                fleetStatus(me.getBoard()),
                opponent.map(p -> fleetStatus(p.getBoard())).orElseGet(GameViewMapper::emptyFleet),
                lastShot == null ? null : new LastShotView(lastShot.shooterId().equals(me.getId()),
                        lastShot.target().row(), lastShot.target().col(), lastShot.result(), lastShot.sunkShip()),
                game.getEvents());
    }

    /**
     * @param revealShips whether afloat ships are shown; false for the opponent board during play
     */
    static CellState[][] renderBoard(Board board, boolean revealShips) {
        CellState[][] grid = emptyGrid();
        if (revealShips) {
            for (Ship ship : board.getShips()) {
                ship.getCells().forEach(c -> grid[c.row()][c.col()] = CellState.SHIP);
            }
        }
        board.getShots().forEach((c, result) -> grid[c.row()][c.col()] = toCell(result));
        for (Ship ship : board.getSunkShips()) {
            for (Coordinate c : ship.getCells()) {
                grid[c.row()][c.col()] = CellState.SUNK;
            }
        }
        return grid;
    }

    private static CellState toCell(ShotResult result) {
        return switch (result) {
            case MISS -> CellState.MISS;
            case HIT -> CellState.HIT;
            case SUNK -> CellState.SUNK;
        };
    }

    private static CellState[][] emptyGrid() {
        CellState[][] grid = new CellState[Board.SIZE][Board.SIZE];
        for (CellState[] row : grid) {
            Arrays.fill(row, CellState.EMPTY);
        }
        return grid;
    }

    private static List<ShipStatusView> fleetStatus(Board board) {
        List<ShipType> remaining = board.getRemainingShipTypes();
        return Arrays.stream(ShipType.values())
                .map(t -> new ShipStatusView(t, t.getDisplayName(), t.getSize(), !remaining.contains(t)))
                .toList();
    }

    private static List<ShipStatusView> emptyFleet() {
        return Arrays.stream(ShipType.values())
                .map(t -> new ShipStatusView(t, t.getDisplayName(), t.getSize(), false))
                .toList();
    }
}
