package com.example.battleship.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class GameCreationRateLimiterTest {

    /** A clock the test can move forward. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final MutableClock clock = new MutableClock();
    private final GameCreationRateLimiter limiter = new GameCreationRateLimiter(3, Duration.ofMinutes(1), clock);

    @Test
    void allowsABurstUpToCapacityThenRejects() {
        for (int i = 0; i < 3; i++) {
            limiter.acquire("203.0.113.7");
        }

        assertThatThrownBy(() -> limiter.acquire("203.0.113.7"))
                .isInstanceOf(RateLimitExceededException.class)
                .satisfies(e -> assertThat(((RateLimitExceededException) e).getRetryAfter())
                        .isEqualTo(Duration.ofSeconds(20)));
    }

    @Test
    void refillsOverTime() {
        for (int i = 0; i < 3; i++) {
            limiter.acquire("203.0.113.7");
        }
        clock.advance(Duration.ofSeconds(20));

        assertThatCode(() -> limiter.acquire("203.0.113.7")).doesNotThrowAnyException();
        assertThatThrownBy(() -> limiter.acquire("203.0.113.7")).isInstanceOf(RateLimitExceededException.class);
    }

    @Test
    void clientsAreLimitedIndependently() {
        for (int i = 0; i < 3; i++) {
            limiter.acquire("203.0.113.7");
        }

        assertThatCode(() -> limiter.acquire("198.51.100.1")).doesNotThrowAnyException();
    }

    @Test
    void ipv6AddressesInTheSameSlash64ShareABucket() {
        limiter.acquire("2001:db8:1:2::1");
        limiter.acquire("2001:db8:1:2:aaaa::2");
        limiter.acquire("2001:db8:1:2:ffff:ffff:ffff:ffff");

        assertThatThrownBy(() -> limiter.acquire("2001:db8:1:2::99")).isInstanceOf(RateLimitExceededException.class);
        assertThatCode(() -> limiter.acquire("2001:db8:1:3::1")).doesNotThrowAnyException();
    }

    @Test
    void purgeForgetsOnlyClientsWhoseBucketHasRefilled() {
        limiter.acquire("203.0.113.7");
        clock.advance(Duration.ofSeconds(30));
        limiter.acquire("198.51.100.1");
        clock.advance(Duration.ofSeconds(30));

        limiter.purgeRefilledBuckets();

        assertThat(limiter.trackedClients()).isEqualTo(1);
    }
}
