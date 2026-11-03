package com.example.battleship.controller;

import java.time.Duration;

/** The client created too many games in a short time. */
public class RateLimitExceededException extends RuntimeException {

    private final Duration retryAfter;

    public RateLimitExceededException(Duration retryAfter) {
        super("Muitas partidas criadas em pouco tempo. Aguarde alguns segundos e tente novamente.");
        this.retryAfter = retryAfter;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }
}
