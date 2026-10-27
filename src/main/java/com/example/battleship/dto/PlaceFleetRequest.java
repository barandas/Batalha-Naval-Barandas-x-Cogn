package com.example.battleship.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record PlaceFleetRequest(
        @NotBlank(message = "playerId é obrigatório.") String playerId,
        @NotNull(message = "Informe os navios.") List<@Valid @NotNull ShipPlacementDto> ships) {
}
