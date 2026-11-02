package com.example.battleship.model;

public record ShipPlacement(ShipType type, Coordinate start, Orientation orientation) {

    public static ShipPlacement of(Ship ship) {
        return new ShipPlacement(ship.getType(), ship.getStart(), ship.getOrientation());
    }
}
