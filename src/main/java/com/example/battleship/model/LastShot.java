package com.example.battleship.model;

public record LastShot(String shooterId, Coordinate target, ShotResult result, ShipType sunkShip) {
}
