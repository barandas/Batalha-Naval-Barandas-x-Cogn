package com.example.battleship.dto;

import jakarta.validation.constraints.NotBlank;

public record PlayerRequest(@NotBlank(message = "playerId é obrigatório.") String playerId) {
}
