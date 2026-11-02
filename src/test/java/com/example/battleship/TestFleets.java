package com.example.battleship;

import com.example.battleship.model.Coordinate;
import com.example.battleship.model.Orientation;
import com.example.battleship.model.ShipPlacement;
import com.example.battleship.model.ShipType;
import java.util.ArrayList;
import java.util.List;

/** Deterministic fleet layouts shared by tests. */
public final class TestFleets {

    private TestFleets() {
    }

    /** Every ship horizontal, starting at column 0, one per row (rows 0..4). */
    public static List<ShipPlacement> stacked() {
        List<ShipPlacement> placements = new ArrayList<>();
        ShipType[] types = ShipType.values();
        for (int i = 0; i < types.length; i++) {
            placements.add(new ShipPlacement(types[i], Coordinate.of(i, 0), Orientation.HORIZONTAL));
        }
        return placements;
    }

    /** All cells occupied by {@link #stacked()}. */
    public static List<Coordinate> stackedCells() {
        List<Coordinate> cells = new ArrayList<>();
        ShipType[] types = ShipType.values();
        for (int i = 0; i < types.length; i++) {
            for (int col = 0; col < types[i].getSize(); col++) {
                cells.add(Coordinate.of(i, col));
            }
        }
        return cells;
    }
}
