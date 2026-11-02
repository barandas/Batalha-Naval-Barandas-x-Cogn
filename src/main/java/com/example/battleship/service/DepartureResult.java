package com.example.battleship.service;

/** What happened to a game after a participant left it, voluntarily or by a persistent disconnection. */
public enum DepartureResult {
    /** No other human was in the game: it was removed. */
    CANCELLED,
    /** Before the battle: the participant was removed and the room accepts a new opponent. */
    SLOT_REOPENED,
    /** During the battle: the remaining player wins by forfeit. */
    FORFEIT,
    /** After the game ended: the result is kept and the remaining player may reopen the slot via rematch. */
    RESULT_KEPT
}
