package com.example.battleship.service;

import com.example.battleship.model.Game;
import com.example.battleship.model.Player;

/** A game together with the player that just created or joined it. */
public record PlayerSession(Game game, Player player) {
}
