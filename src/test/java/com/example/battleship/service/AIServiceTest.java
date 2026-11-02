package com.example.battleship.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.battleship.model.Board;
import com.example.battleship.model.Coordinate;
import com.example.battleship.model.Difficulty;
import com.example.battleship.model.Orientation;
import com.example.battleship.model.ShipType;
import com.example.battleship.model.ShotOutcome;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class AIServiceTest {

    @ParameterizedTest
    @EnumSource(Difficulty.class)
    void finishesAGameWithoutRepeatingOrLeavingTheBoard(Difficulty difficulty) {
        for (int seed = 0; seed < 20; seed++) {
            Board board = randomBoard(seed);
            AIService ai = new AIService(new Random(seed));
            Set<Coordinate> fired = new HashSet<>();

            int shots = 0;
            while (!board.allShipsSunk()) {
                Coordinate target = ai.chooseShot(difficulty, board);
                assertThat(target.isWithinBoard()).isTrue();
                assertThat(fired.add(target)).as("repeated shot %s", target).isTrue();
                board.receiveShot(target);
                shots++;
            }
            assertThat(shots).isLessThanOrEqualTo(100);
        }
    }

    @ParameterizedTest
    @EnumSource(Difficulty.class)
    void picksTheOnlyRemainingCell(Difficulty difficulty) {
        Board board = new Board();
        board.placeShip(ShipType.DESTROYER, Coordinate.of(9, 8), Orientation.HORIZONTAL);
        for (int row = 0; row < Board.SIZE; row++) {
            for (int col = 0; col < Board.SIZE; col++) {
                if (!(row == 9 && col == 9)) {
                    board.receiveShot(Coordinate.of(row, col));
                }
            }
        }

        assertThat(new AIService(new Random(1)).chooseShot(difficulty, board)).isEqualTo(Coordinate.of(9, 9));
    }

    @Test
    void mediumTargetsNeighboursAfterASingleHit() {
        Board board = new Board();
        board.placeShip(ShipType.BATTLESHIP, Coordinate.of(5, 5), Orientation.VERTICAL);
        board.receiveShot(Coordinate.of(5, 5));

        for (int seed = 0; seed < 30; seed++) {
            Coordinate shot = new AIService(new Random(seed)).chooseShot(Difficulty.MEDIUM, board);
            assertThat(shot).isIn(Coordinate.of(4, 5), Coordinate.of(6, 5), Coordinate.of(5, 4), Coordinate.of(5, 6));
        }
    }

    @Test
    void mediumFollowsTheLineOnceTwoHitsAreAligned() {
        Board board = new Board();
        board.placeShip(ShipType.CARRIER, Coordinate.of(3, 2), Orientation.HORIZONTAL);
        board.receiveShot(Coordinate.of(3, 4));
        board.receiveShot(Coordinate.of(3, 5));

        for (int seed = 0; seed < 30; seed++) {
            Coordinate shot = new AIService(new Random(seed)).chooseShot(Difficulty.MEDIUM, board);
            assertThat(shot).isIn(Coordinate.of(3, 3), Coordinate.of(3, 6));
        }
    }

    @Test
    void mediumSkipsBlockedLineEndAndContinuesOnTheOtherSide() {
        Board board = new Board();
        board.placeShip(ShipType.CARRIER, Coordinate.of(3, 2), Orientation.HORIZONTAL);
        board.receiveShot(Coordinate.of(3, 4));
        board.receiveShot(Coordinate.of(3, 5));
        board.receiveShot(Coordinate.of(3, 6)); // hit
        board.receiveShot(Coordinate.of(3, 7)); // miss: right end is closed

        Coordinate shot = new AIService(new Random(7)).chooseShot(Difficulty.MEDIUM, board);

        assertThat(shot).isEqualTo(Coordinate.of(3, 3));
    }

    @Test
    void mediumReturnsToHuntingAfterSinking() {
        Board board = new Board();
        board.placeShip(ShipType.DESTROYER, Coordinate.of(0, 0), Orientation.HORIZONTAL);
        board.placeShip(ShipType.CARRIER, Coordinate.of(9, 0), Orientation.HORIZONTAL);
        board.receiveShot(Coordinate.of(0, 0));
        board.receiveShot(Coordinate.of(0, 1));

        AIService ai = new AIService(new Random(3));
        AIService.Knowledge knowledge = AIService.Knowledge.from(board);

        assertThat(knowledge.unresolvedHits()).isEmpty();
        assertThat(ai.targetCandidates(knowledge)).isEmpty();
    }

    @Test
    void hardRespectsCheckerboardParityWhileHunting() {
        Board board = randomBoard(42);
        AIService ai = new AIService(new Random(42));

        for (int i = 0; i < 15; i++) {
            if (!board.getUnresolvedHits().isEmpty()) {
                break;
            }
            Coordinate shot = ai.chooseShot(Difficulty.HARD, board);
            int smallest = board.getRemainingShipTypes().stream().mapToInt(ShipType::getSize).min().orElseThrow();
            assertThat(AIService.hasParity(shot, smallest)).as("parity of %s", shot).isTrue();
            board.receiveShot(shot);
        }
    }

    @Test
    void hardOpeningShotFavoursTheCentre() {
        Coordinate shot = new AIService(new Random(0)).chooseShot(Difficulty.HARD, new Board());

        assertThat(shot.row()).isBetween(3, 6);
        assertThat(shot.col()).isBetween(3, 6);
        assertThat((shot.row() + shot.col()) % 2).isZero();
    }

    @Test
    void hardTargetsAroundAnUnresolvedHit() {
        Board board = new Board();
        board.placeShip(ShipType.CRUISER, Coordinate.of(0, 0), Orientation.VERTICAL);
        board.receiveShot(Coordinate.of(0, 0));

        Coordinate shot = new AIService(new Random(5)).chooseShot(Difficulty.HARD, board);

        assertThat(shot).isIn(Coordinate.of(1, 0), Coordinate.of(0, 1));
    }

    @Test
    void hardDensityIgnoresPlacementsThroughMisses() {
        Board board = new Board();
        board.placeShip(ShipType.DESTROYER, Coordinate.of(9, 8), Orientation.HORIZONTAL);
        for (int col = 0; col < Board.SIZE; col++) {
            board.receiveShot(Coordinate.of(4, col));
        }

        int[][] density = new AIService(new Random(1)).densityMap(AIService.Knowledge.from(board));

        for (int col = 0; col < Board.SIZE; col++) {
            assertThat(density[4][col]).isZero();
        }
        assertThat(density[2][5]).isPositive();
    }

    @Test
    void smarterDifficultiesNeedFewerShotsOnAverage() {
        double easy = averageShots(Difficulty.EASY);
        double medium = averageShots(Difficulty.MEDIUM);
        double hard = averageShots(Difficulty.HARD);

        assertThat(medium).isLessThan(easy);
        assertThat(hard).isLessThan(medium);
    }

    @Test
    void parityHelper() {
        assertThat(AIService.hasParity(Coordinate.of(0, 0), 2)).isTrue();
        assertThat(AIService.hasParity(Coordinate.of(0, 1), 2)).isFalse();
        assertThat(AIService.hasParity(Coordinate.of(1, 2), 3)).isTrue();
    }

    private static double averageShots(Difficulty difficulty) {
        int games = 40;
        int total = 0;
        for (int seed = 0; seed < games; seed++) {
            Board board = randomBoard(1000 + seed);
            AIService ai = new AIService(new Random(seed));
            while (!board.allShipsSunk()) {
                ShotOutcome ignored = board.receiveShot(ai.chooseShot(difficulty, board));
                total++;
            }
        }
        return (double) total / games;
    }

    private static Board randomBoard(long seed) {
        Board board = new Board();
        board.placeFleetRandomly(new Random(seed));
        assertThat(board.getShips()).hasSize(List.of(ShipType.values()).size());
        return board;
    }
}
