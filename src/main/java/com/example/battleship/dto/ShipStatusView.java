package com.example.battleship.dto;

import com.example.battleship.model.ShipType;

public record ShipStatusView(ShipType type, String name, int size, boolean sunk) {
}
