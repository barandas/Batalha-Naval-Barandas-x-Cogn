package com.example.battleship.model;

/** Why a finished game ended. */
public enum GameEndReason {
    FLEET_DESTROYED,
    /** The loser left voluntarily during the battle (forfeit). */
    OPPONENT_LEFT,
    /** The loser lost the connection and did not come back within the grace period. */
    OPPONENT_DISCONNECTED
}
