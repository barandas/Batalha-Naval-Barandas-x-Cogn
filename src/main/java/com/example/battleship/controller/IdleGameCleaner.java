package com.example.battleship.controller;

import com.example.battleship.service.GameService;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically frees games that are idle and no longer open in any browser tab. */
@Component
public class IdleGameCleaner {

    private static final Logger log = LoggerFactory.getLogger(IdleGameCleaner.class);

    private final GameService gameService;
    private final PresenceTracker presenceTracker;
    private final Duration idleTimeout;

    public IdleGameCleaner(GameService gameService, PresenceTracker presenceTracker,
                           @Value("${battleship.idle-timeout:30m}") Duration idleTimeout) {
        this.gameService = gameService;
        this.presenceTracker = presenceTracker;
        this.idleTimeout = idleTimeout;
    }

    @Scheduled(fixedDelayString = "${battleship.cleanup-interval:PT5M}",
            initialDelayString = "${battleship.cleanup-interval:PT5M}")
    public void evictIdleGames() {
        int evicted = presenceTracker.withPresenceFrozen(
                () -> gameService.evictIdleGames(idleTimeout, presenceTracker::isWatched));
        if (evicted > 0) {
            log.info("Removed {} idle game(s) from memory", evicted);
        }
    }
}
