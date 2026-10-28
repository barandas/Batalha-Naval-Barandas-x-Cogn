package com.example.battleship.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Ship {

    private final ShipType type;
    private final Coordinate start;
    private final Orientation orientation;
    private final List<Coordinate> cells;
    private final Set<Coordinate> hits = new HashSet<>();

    public Ship(ShipType type, Coordinate start, Orientation orientation) {
        this.type = type;
        this.start = start;
        this.orientation = orientation;
        this.cells = computeCells(type, start, orientation);
    }

    public static List<Coordinate> computeCells(ShipType type, Coordinate start, Orientation orientation) {
        List<Coordinate> result = new ArrayList<>(type.getSize());
        for (int i = 0; i < type.getSize(); i++) {
            result.add(start.translate(orientation.deltaRow() * i, orientation.deltaCol() * i));
        }
        return Collections.unmodifiableList(result);
    }

    public boolean occupies(Coordinate coordinate) {
        return cells.contains(coordinate);
    }

    /** Registers a hit and returns true if the coordinate belongs to this ship. */
    public boolean registerHit(Coordinate coordinate) {
        if (!occupies(coordinate)) {
            return false;
        }
        hits.add(coordinate);
        return true;
    }

    public boolean isSunk() {
        return hits.size() == cells.size();
    }

    public ShipType getType() {
        return type;
    }

    public Coordinate getStart() {
        return start;
    }

    public Orientation getOrientation() {
        return orientation;
    }

    public List<Coordinate> getCells() {
        return cells;
    }

    public Set<Coordinate> getHits() {
        return Collections.unmodifiableSet(hits);
    }
}
