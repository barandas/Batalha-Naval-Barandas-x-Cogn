package com.example.battleship.model;

public enum Difficulty {
    /** Shoots at random untouched cells. */
    EASY,
    /** Hunt/target: random hunting, then probes around hits until the ship sinks. */
    MEDIUM,
    /** Probability density over remaining ships combined with checkerboard parity. */
    HARD
}
