package com.example.battleship.model;

public enum Orientation {
    HORIZONTAL(0, 1),
    VERTICAL(1, 0);

    private final int deltaRow;
    private final int deltaCol;

    Orientation(int deltaRow, int deltaCol) {
        this.deltaRow = deltaRow;
        this.deltaCol = deltaCol;
    }

    public int deltaRow() {
        return deltaRow;
    }

    public int deltaCol() {
        return deltaCol;
    }

    public Orientation rotate() {
        return this == HORIZONTAL ? VERTICAL : HORIZONTAL;
    }
}
