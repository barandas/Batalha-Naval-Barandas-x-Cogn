package com.example.battleship.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CoordinateTest {

    @Test
    void boundsCheck() {
        assertThat(Coordinate.of(0, 0).isWithinBoard()).isTrue();
        assertThat(Coordinate.of(9, 9).isWithinBoard()).isTrue();
        assertThat(Coordinate.of(10, 0).isWithinBoard()).isFalse();
        assertThat(Coordinate.of(0, -1).isWithinBoard()).isFalse();
    }

    @Test
    void labelUsesRowLetterAndOneBasedColumn() {
        assertThat(Coordinate.of(0, 0).label()).isEqualTo("A1");
        assertThat(Coordinate.of(9, 9).label()).isEqualTo("J10");
    }

    @Test
    void neighborsStayInsideBoard() {
        assertThat(Coordinate.of(0, 0).orthogonalNeighbors())
                .containsExactlyInAnyOrder(Coordinate.of(1, 0), Coordinate.of(0, 1));
        assertThat(Coordinate.of(5, 5).orthogonalNeighbors()).hasSize(4);
    }

    @Test
    void shipTypesHaveStandardSizes() {
        assertThat(ShipType.CARRIER.getSize()).isEqualTo(5);
        assertThat(ShipType.BATTLESHIP.getSize()).isEqualTo(4);
        assertThat(ShipType.CRUISER.getSize()).isEqualTo(3);
        assertThat(ShipType.SUBMARINE.getSize()).isEqualTo(3);
        assertThat(ShipType.DESTROYER.getSize()).isEqualTo(2);
    }
}
