package com.example.battleship.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ShotRequest(
        @NotBlank(message = "playerId é obrigatório.") String playerId,
        @NotNull @Min(0) @Max(9) Integer row,
        @NotNull @Min(0) @Max(9) Integer col) {
}
