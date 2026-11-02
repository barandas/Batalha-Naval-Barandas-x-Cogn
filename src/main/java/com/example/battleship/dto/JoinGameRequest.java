package com.example.battleship.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record JoinGameRequest(
        @NotBlank(message = "Informe o código da sala.") @Size(max = 12, message = "Código de sala inválido.")
        String roomCode,
        @NotBlank(message = "Informe seu nome.") @Size(max = 20, message = "O nome deve ter no máximo 20 caracteres.")
        String playerName) {
}
