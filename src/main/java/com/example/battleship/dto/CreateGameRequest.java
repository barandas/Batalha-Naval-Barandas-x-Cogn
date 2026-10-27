package com.example.battleship.dto;

import com.example.battleship.model.Difficulty;
import com.example.battleship.model.GameMode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateGameRequest(
        @NotBlank(message = "Informe seu nome.") @Size(max = 20, message = "O nome deve ter no máximo 20 caracteres.")
        String playerName,
        @NotNull(message = "Informe o modo de jogo.") GameMode mode,
        Difficulty difficulty) {
}
