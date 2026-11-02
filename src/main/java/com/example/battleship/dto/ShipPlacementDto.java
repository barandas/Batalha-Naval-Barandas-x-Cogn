package com.example.battleship.dto;

import com.example.battleship.model.Coordinate;
import com.example.battleship.model.Orientation;
import com.example.battleship.model.ShipPlacement;
import com.example.battleship.model.ShipType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ShipPlacementDto(
        @NotNull(message = "Tipo de navio obrigatório.") ShipType type,
        @NotNull @Min(0) @Max(9) Integer row,
        @NotNull @Min(0) @Max(9) Integer col,
        @NotNull(message = "Orientação obrigatória.") Orientation orientation) {

    public ShipPlacement toModel() {
        return new ShipPlacement(type, Coordinate.of(row, col), orientation);
    }

    public static ShipPlacementDto from(ShipPlacement placement) {
        return new ShipPlacementDto(placement.type(), placement.start().row(), placement.start().col(),
                placement.orientation());
    }
}
