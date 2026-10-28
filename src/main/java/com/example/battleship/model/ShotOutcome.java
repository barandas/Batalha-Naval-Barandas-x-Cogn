package com.example.battleship.model;

/**
 * Result of a shot against a board. {@code sunkShip} is only set when {@code result} is {@link ShotResult#SUNK}.
 */
public record ShotOutcome(Coordinate target, ShotResult result, ShipType sunkShip, boolean fleetDestroyed) {
}
