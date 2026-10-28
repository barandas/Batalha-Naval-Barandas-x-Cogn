package com.example.battleship.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class BoardTest {

    private Board board;

    @BeforeEach
    void setUp() {
        board = new Board();
    }

    @Test
    void placesShipHorizontallyAndVertically() {
        Ship carrier = board.placeShip(ShipType.CARRIER, Coordinate.of(0, 0), Orientation.HORIZONTAL);
        Ship destroyer = board.placeShip(ShipType.DESTROYER, Coordinate.of(5, 9), Orientation.VERTICAL);

        assertThat(carrier.getCells()).containsExactly(
                Coordinate.of(0, 0), Coordinate.of(0, 1), Coordinate.of(0, 2), Coordinate.of(0, 3), Coordinate.of(0, 4));
        assertThat(destroyer.getCells()).containsExactly(Coordinate.of(5, 9), Coordinate.of(6, 9));
        assertThat(board.shipAt(Coordinate.of(0, 4))).contains(carrier);
        assertThat(board.shipAt(Coordinate.of(1, 0))).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "CARRIER, 0, 6, HORIZONTAL",
            "CARRIER, 6, 0, VERTICAL",
            "DESTROYER, 9, 9, HORIZONTAL",
            "DESTROYER, 9, 9, VERTICAL",
            "SUBMARINE, -1, 0, HORIZONTAL",
            "CRUISER, 0, 10, VERTICAL"
    })
    void rejectsPlacementOutsideBoard(ShipType type, int row, int col, Orientation orientation) {
        Coordinate start = Coordinate.of(row, col);

        assertThat(board.canPlace(type, start, orientation)).isFalse();
        assertThatThrownBy(() -> board.placeShip(type, start, orientation))
                .isInstanceOf(InvalidMoveException.class)
                .hasMessageContaining("limites");
        assertThat(board.getShips()).isEmpty();
    }

    @Test
    void acceptsShipTouchingTheEdge() {
        assertThat(board.canPlace(ShipType.CARRIER, Coordinate.of(9, 5), Orientation.HORIZONTAL)).isTrue();
        assertThat(board.canPlace(ShipType.CARRIER, Coordinate.of(5, 9), Orientation.VERTICAL)).isTrue();
    }

    @Test
    void rejectsOverlappingShips() {
        board.placeShip(ShipType.CARRIER, Coordinate.of(2, 2), Orientation.HORIZONTAL);

        assertThat(board.canPlace(ShipType.BATTLESHIP, Coordinate.of(0, 4), Orientation.VERTICAL)).isFalse();
        assertThatThrownBy(() -> board.placeShip(ShipType.BATTLESHIP, Coordinate.of(0, 4), Orientation.VERTICAL))
                .isInstanceOf(InvalidMoveException.class)
                .hasMessageContaining("sobrepõe");
    }

    @Test
    void rejectsSameShipTypeTwice() {
        board.placeShip(ShipType.DESTROYER, Coordinate.of(0, 0), Orientation.HORIZONTAL);

        assertThatThrownBy(() -> board.placeShip(ShipType.DESTROYER, Coordinate.of(5, 5), Orientation.HORIZONTAL))
                .isInstanceOf(InvalidMoveException.class);
    }

    @Test
    void fleetIsCompleteOnlyWithAllFiveShips() {
        placeStackedFleet();
        assertThat(board.isFleetComplete()).isTrue();

        board.removeShip(ShipType.DESTROYER);
        assertThat(board.isFleetComplete()).isFalse();
    }

    @Test
    void shotReportsMissHitAndSunk() {
        board.placeShip(ShipType.DESTROYER, Coordinate.of(3, 3), Orientation.HORIZONTAL);

        assertThat(board.receiveShot(Coordinate.of(0, 0)).result()).isEqualTo(ShotResult.MISS);
        assertThat(board.receiveShot(Coordinate.of(3, 3)).result()).isEqualTo(ShotResult.HIT);

        ShotOutcome sunk = board.receiveShot(Coordinate.of(3, 4));
        assertThat(sunk.result()).isEqualTo(ShotResult.SUNK);
        assertThat(sunk.sunkShip()).isEqualTo(ShipType.DESTROYER);
        assertThat(board.getShots()).hasSize(3);
    }

    @Test
    void repeatedShotIsRejected() {
        board.receiveShot(Coordinate.of(4, 4));

        assertThatThrownBy(() -> board.receiveShot(Coordinate.of(4, 4)))
                .isInstanceOf(InvalidMoveException.class)
                .hasMessageContaining("E5");
    }

    @Test
    void shotOutsideBoardIsRejected() {
        assertThatThrownBy(() -> board.receiveShot(Coordinate.of(10, 0))).isInstanceOf(InvalidMoveException.class);
        assertThatThrownBy(() -> board.receiveShot(Coordinate.of(0, -1))).isInstanceOf(InvalidMoveException.class);
    }

    @Test
    void fleetDestroyedOnlyWhenLastShipSinks() {
        placeStackedFleet();
        int sunkCount = 0;
        ShotOutcome last = null;
        for (int row = 0; row < ShipType.values().length; row++) {
            for (int col = 0; col < ShipType.values()[row].getSize(); col++) {
                last = board.receiveShot(Coordinate.of(row, col));
                if (last.result() == ShotResult.SUNK) {
                    sunkCount++;
                    assertThat(last.fleetDestroyed()).isEqualTo(sunkCount == ShipType.values().length);
                }
            }
        }
        assertThat(last).isNotNull();
        assertThat(last.fleetDestroyed()).isTrue();
        assertThat(board.allShipsSunk()).isTrue();
        assertThat(board.getRemainingShipTypes()).isEmpty();
    }

    @Test
    void cannotRepositionShipsAfterShotsWereFired() {
        board.receiveShot(Coordinate.of(9, 9));

        assertThatThrownBy(() -> board.placeShip(ShipType.CARRIER, Coordinate.of(0, 0), Orientation.HORIZONTAL))
                .isInstanceOf(InvalidMoveException.class);
    }

    @Test
    void unresolvedHitsExcludeSunkShips() {
        board.placeShip(ShipType.DESTROYER, Coordinate.of(0, 0), Orientation.HORIZONTAL);
        board.placeShip(ShipType.CRUISER, Coordinate.of(5, 5), Orientation.VERTICAL);
        board.receiveShot(Coordinate.of(0, 0));
        board.receiveShot(Coordinate.of(0, 1));
        board.receiveShot(Coordinate.of(5, 5));
        board.receiveShot(Coordinate.of(9, 9));

        assertThat(board.getUnresolvedHits()).containsExactly(Coordinate.of(5, 5));
        assertThat(board.getRemainingShipTypes()).doesNotContain(ShipType.DESTROYER);
    }

    @Test
    void randomFleetIsCompleteValidAndNonOverlapping() {
        for (int seed = 0; seed < 200; seed++) {
            board.placeFleetRandomly(new Random(seed));

            assertThat(board.isFleetComplete()).isTrue();
            Set<Coordinate> occupied = new HashSet<>();
            board.getShips().forEach(ship -> ship.getCells().forEach(cell -> {
                assertThat(cell.isWithinBoard()).isTrue();
                assertThat(occupied.add(cell)).as("overlap at %s", cell).isTrue();
            }));
            assertThat(occupied).hasSize(17);
        }
    }

    @Test
    void resetClearsShipsAndShots() {
        placeStackedFleet();
        board.receiveShot(Coordinate.of(0, 0));

        board.reset();

        assertThat(board.getShips()).isEmpty();
        assertThat(board.getShots()).isEmpty();
        assertThat(board.allShipsSunk()).isFalse();
    }

    private void placeStackedFleet() {
        ShipType[] types = ShipType.values();
        for (int i = 0; i < types.length; i++) {
            board.placeShip(types[i], Coordinate.of(i, 0), Orientation.HORIZONTAL);
        }
    }
}
