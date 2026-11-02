package com.example.battleship.dto;

import com.example.battleship.model.ShipType;
import com.example.battleship.model.ShotResult;

public record LastShotView(boolean byYou, int row, int col, ShotResult result, ShipType sunkShip) {
}
