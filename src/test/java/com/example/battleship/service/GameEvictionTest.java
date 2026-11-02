package com.example.battleship.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.battleship.model.Difficulty;
import com.example.battleship.model.GameMode;
import com.example.battleship.repository.GameRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Idle games are dropped from memory unless a page is still open on them. */
class GameEvictionTest {

    private static final Duration IDLE = Duration.ofMinutes(30);

    private final GameRepository repository = new GameRepository();
    private final GameService service = new GameService(repository, new AIService(new Random(1)), new Random(1));

    private GameService at(Instant now) {
        return new GameService(repository, new AIService(new Random(1)), new Random(1),
                Clock.fixed(now, ZoneOffset.UTC), GameService.DEFAULT_RECONNECT_GRACE);
    }

    @Test
    void idleUnwatchedGamesAreRemovedAndTheirCodeFreed() {
        PlayerSession single = service.createGame("Ana", GameMode.SINGLE_PLAYER, Difficulty.EASY);
        PlayerSession room = service.createGame("Bia", GameMode.MULTIPLAYER, null);

        int evicted = at(Instant.now().plus(IDLE).plusSeconds(1)).evictIdleGames(IDLE, id -> false);

        assertThat(evicted).isEqualTo(2);
        assertThat(repository.findAll()).isEmpty();
        assertThat(repository.findById(single.game().getId())).isEmpty();
        assertThat(repository.existsByRoomCode(room.game().getRoomCode())).isFalse();
    }

    @Test
    void recentlyActiveGamesAreKept() {
        service.createGame("Ana", GameMode.SINGLE_PLAYER, Difficulty.EASY);

        assertThat(at(Instant.now().plus(IDLE).minusSeconds(60)).evictIdleGames(IDLE, id -> false)).isZero();
        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    void gamesStillOpenInABrowserAreKept() {
        PlayerSession watched = service.createGame("Ana", GameMode.SINGLE_PLAYER, Difficulty.EASY);
        service.createGame("Bia", GameMode.SINGLE_PLAYER, Difficulty.EASY);

        int evicted = at(Instant.now().plus(Duration.ofHours(2)))
                .evictIdleGames(IDLE, id -> id.equals(watched.game().getId()));

        assertThat(evicted).isEqualTo(1);
        assertThat(repository.findById(watched.game().getId())).isPresent();
    }

    @Test
    void anActionRefreshesTheIdleTimer() throws InterruptedException {
        PlayerSession session = service.createGame("Ana", GameMode.SINGLE_PLAYER, Difficulty.EASY);
        Instant before = session.game().getLastActivity();
        Thread.sleep(5);

        service.submitFleet(session.game().getId(), session.player().getId(), service.generateRandomFleet());

        assertThat(session.game().getLastActivity()).isAfter(before);
    }
}
