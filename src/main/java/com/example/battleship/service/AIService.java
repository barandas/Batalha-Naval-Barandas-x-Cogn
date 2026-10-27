package com.example.battleship.service;

import com.example.battleship.model.Board;
import com.example.battleship.model.Coordinate;
import com.example.battleship.model.Difficulty;
import com.example.battleship.model.Orientation;
import com.example.battleship.model.Ship;
import com.example.battleship.model.ShipType;
import com.example.battleship.model.ShotResult;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.stereotype.Service;

/**
 * Chooses shots for the computer opponent. Only public information is used: which cells were shot,
 * whether they were hits, and which ships have been sunk. Ship positions of afloat ships are never read.
 */
@Service
public class AIService {

    private static final int TARGET_WEIGHT = 25;

    private final Random random;

    public AIService() {
        this(new Random());
    }

    public AIService(Random random) {
        this.random = random;
    }

    public Coordinate chooseShot(Difficulty difficulty, Board targetBoard) {
        Knowledge knowledge = Knowledge.from(targetBoard);
        if (knowledge.unshotCells().isEmpty()) {
            throw new IllegalStateException("Não há mais células disponíveis para atirar.");
        }
        return switch (difficulty == null ? Difficulty.MEDIUM : difficulty) {
            case EASY -> randomShot(knowledge);
            case MEDIUM -> huntTargetShot(knowledge);
            case HARD -> probabilityShot(knowledge);
        };
    }

    Coordinate randomShot(Knowledge knowledge) {
        return pickRandom(knowledge.unshotCells());
    }

    Coordinate huntTargetShot(Knowledge knowledge) {
        List<Coordinate> targets = targetCandidates(knowledge);
        return targets.isEmpty() ? randomShot(knowledge) : pickRandom(targets);
    }

    Coordinate probabilityShot(Knowledge knowledge) {
        int[][] density = densityMap(knowledge);
        boolean targeting = !knowledge.unresolvedHits().isEmpty();
        int smallestShip = knowledge.remainingShips().stream().mapToInt(ShipType::getSize).min().orElse(2);

        List<Coordinate> candidates = bestCells(knowledge, density, cell -> targeting || hasParity(cell, smallestShip));
        if (candidates.isEmpty()) {
            candidates = bestCells(knowledge, density, cell -> true);
        }
        if (candidates.isEmpty()) {
            return huntTargetShot(knowledge);
        }
        return pickRandom(candidates);
    }

    /** Checkerboard parity: every ship of length >= n must cover at least one cell where (row + col) % n == 0. */
    static boolean hasParity(Coordinate cell, int smallestShipSize) {
        return (cell.row() + cell.col()) % smallestShipSize == 0;
    }

    int[][] densityMap(Knowledge knowledge) {
        int[][] density = new int[Board.SIZE][Board.SIZE];
        boolean targeting = !knowledge.unresolvedHits().isEmpty();
        for (ShipType type : knowledge.remainingShips()) {
            for (int row = 0; row < Board.SIZE; row++) {
                for (int col = 0; col < Board.SIZE; col++) {
                    for (Orientation orientation : Orientation.values()) {
                        addPlacementWeight(knowledge, density, targeting,
                                Ship.computeCells(type, Coordinate.of(row, col), orientation));
                    }
                }
            }
        }
        return density;
    }

    private void addPlacementWeight(Knowledge knowledge, int[][] density, boolean targeting, List<Coordinate> cells) {
        int coveredHits = 0;
        for (Coordinate cell : cells) {
            if (!cell.isWithinBoard() || knowledge.blockedCells().contains(cell)) {
                return;
            }
            if (knowledge.unresolvedHits().contains(cell)) {
                coveredHits++;
            }
        }
        if (targeting && coveredHits == 0) {
            return;
        }
        int weight = targeting ? TARGET_WEIGHT * coveredHits : 1;
        for (Coordinate cell : cells) {
            if (!knowledge.shotCells().contains(cell)) {
                density[cell.row()][cell.col()] += weight;
            }
        }
    }

    private List<Coordinate> bestCells(Knowledge knowledge, int[][] density, Predicate<Coordinate> filter) {
        int best = 0;
        List<Coordinate> result = new ArrayList<>();
        for (Coordinate cell : knowledge.unshotCells()) {
            if (!filter.test(cell)) {
                continue;
            }
            int score = density[cell.row()][cell.col()];
            if (score > best) {
                best = score;
                result.clear();
                result.add(cell);
            } else if (score == best && score > 0) {
                result.add(cell);
            }
        }
        return result;
    }

    /**
     * Target mode: if two or more aligned hits exist, extend the line at both ends; otherwise probe the
     * orthogonal neighbours of every unresolved hit.
     */
    List<Coordinate> targetCandidates(Knowledge knowledge) {
        Set<Coordinate> hits = knowledge.unresolvedHits();
        if (hits.isEmpty()) {
            return List.of();
        }
        Set<Coordinate> lineEnds = new LinkedHashSet<>();
        for (Coordinate hit : hits) {
            for (Orientation orientation : Orientation.values()) {
                int dr = orientation.deltaRow();
                int dc = orientation.deltaCol();
                if (!hits.contains(hit.translate(dr, dc)) || hits.contains(hit.translate(-dr, -dc))) {
                    continue; // only start from the first cell of a run of length >= 2
                }
                Coordinate end = hit;
                while (hits.contains(end.translate(dr, dc))) {
                    end = end.translate(dr, dc);
                }
                addIfShootable(knowledge, lineEnds, hit.translate(-dr, -dc));
                addIfShootable(knowledge, lineEnds, end.translate(dr, dc));
            }
        }
        if (!lineEnds.isEmpty()) {
            return new ArrayList<>(lineEnds);
        }
        Set<Coordinate> neighbours = new LinkedHashSet<>();
        for (Coordinate hit : hits) {
            for (Coordinate neighbour : hit.orthogonalNeighbors()) {
                addIfShootable(knowledge, neighbours, neighbour);
            }
        }
        return new ArrayList<>(neighbours);
    }

    private static void addIfShootable(Knowledge knowledge, Set<Coordinate> target, Coordinate cell) {
        if (cell.isWithinBoard() && !knowledge.shotCells().contains(cell)) {
            target.add(cell);
        }
    }

    private Coordinate pickRandom(List<Coordinate> cells) {
        return cells.get(random.nextInt(cells.size()));
    }

    /** Snapshot of what the shooter legitimately knows about the opponent board. */
    record Knowledge(Set<Coordinate> shotCells,
                     Set<Coordinate> unresolvedHits,
                     Set<Coordinate> blockedCells,
                     List<ShipType> remainingShips,
                     List<Coordinate> unshotCells) {

        static Knowledge from(Board board) {
            Map<Coordinate, ShotResult> shots = board.getShots();
            Set<Coordinate> shotCells = new HashSet<>(shots.keySet());
            Set<Coordinate> unresolvedHits = board.getUnresolvedHits();
            Set<Coordinate> blocked = new HashSet<>();
            shots.forEach((cell, result) -> {
                if (!unresolvedHits.contains(cell)) {
                    blocked.add(cell); // misses and cells of sunk ships cannot host a remaining ship
                }
            });
            List<Coordinate> unshot = new ArrayList<>();
            for (int row = 0; row < Board.SIZE; row++) {
                for (int col = 0; col < Board.SIZE; col++) {
                    Coordinate cell = Coordinate.of(row, col);
                    if (!shotCells.contains(cell)) {
                        unshot.add(cell);
                    }
                }
            }
            return new Knowledge(shotCells, unresolvedHits, blocked, board.getRemainingShipTypes(), unshot);
        }
    }
}
