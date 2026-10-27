package com.example.battleship.model;

import java.util.ArrayList;
import java.util.List;

public record Coordinate(int row, int col) {

    private static final String ROW_LABELS = "ABCDEFGHIJ";

    public static Coordinate of(int row, int col) {
        return new Coordinate(row, col);
    }

    public boolean isWithinBoard() {
        return row >= 0 && row < Board.SIZE && col >= 0 && col < Board.SIZE;
    }

    public Coordinate translate(int deltaRow, int deltaCol) {
        return new Coordinate(row + deltaRow, col + deltaCol);
    }

    public List<Coordinate> orthogonalNeighbors() {
        List<Coordinate> neighbors = new ArrayList<>(4);
        for (Coordinate candidate : List.of(translate(-1, 0), translate(1, 0), translate(0, -1), translate(0, 1))) {
            if (candidate.isWithinBoard()) {
                neighbors.add(candidate);
            }
        }
        return neighbors;
    }

    /** Human-friendly label such as "B7" (row letter + 1-based column). */
    public String label() {
        if (!isWithinBoard()) {
            return "(" + row + "," + col + ")";
        }
        return ROW_LABELS.charAt(row) + String.valueOf(col + 1);
    }
}
