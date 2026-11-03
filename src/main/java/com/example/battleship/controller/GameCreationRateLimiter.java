package com.example.battleship.controller;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Limits how many games one client address may create: a token bucket that holds {@code capacity} creations and
 * refills completely every {@code period}. IPv6 clients are grouped by their /64 prefix, since a single host usually
 * owns a whole /64.
 */
@Component
public class GameCreationRateLimiter {

    private static final class Bucket {
        private double tokens;
        private long updatedAtMillis;

        private Bucket(double tokens, long updatedAtMillis) {
            this.tokens = tokens;
            this.updatedAtMillis = updatedAtMillis;
        }
    }

    private final int capacity;
    private final long periodMillis;
    private final Clock clock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Autowired
    public GameCreationRateLimiter(@Value("${battleship.create-rate-limit.capacity:10}") int capacity,
                                   @Value("${battleship.create-rate-limit.period:1m}") Duration period) {
        this(capacity, period, Clock.systemUTC());
    }

    public GameCreationRateLimiter(int capacity, Duration period, Clock clock) {
        if (capacity < 1 || period.isNegative() || period.isZero()) {
            throw new IllegalArgumentException("capacity and period must be positive");
        }
        this.capacity = capacity;
        this.periodMillis = period.toMillis();
        this.clock = clock;
    }

    /** Consumes one creation for the client, or throws {@link RateLimitExceededException} if none is left. */
    public void acquire(String remoteAddress) {
        long now = clock.millis();
        long[] retryAfterMillis = {0};
        buckets.compute(clientKey(remoteAddress), (key, bucket) -> {
            Bucket b = bucket == null ? new Bucket(capacity, now) : bucket;
            refill(b, now);
            if (b.tokens >= 1) {
                b.tokens -= 1;
            } else {
                retryAfterMillis[0] = (long) Math.ceil((1 - b.tokens) * periodMillis / capacity);
            }
            return b;
        });
        if (retryAfterMillis[0] > 0) {
            throw new RateLimitExceededException(Duration.ofMillis(retryAfterMillis[0]));
        }
    }

    /** Forgets clients whose bucket is full again, so the table only holds recently active addresses. */
    @Scheduled(fixedDelayString = "${battleship.create-rate-limit.period:1m}")
    public void purgeRefilledBuckets() {
        long now = clock.millis();
        for (String key : buckets.keySet()) {
            buckets.computeIfPresent(key, (k, b) -> now - b.updatedAtMillis >= periodMillis ? null : b);
        }
    }

    int trackedClients() {
        return buckets.size();
    }

    private void refill(Bucket bucket, long now) {
        long elapsed = Math.max(0, now - bucket.updatedAtMillis);
        bucket.tokens = Math.min(capacity, bucket.tokens + (double) elapsed * capacity / periodMillis);
        bucket.updatedAtMillis = now;
    }

    static String clientKey(String remoteAddress) {
        if (remoteAddress == null || remoteAddress.isBlank()) {
            return "unknown";
        }
        String address = remoteAddress.trim();
        if (!address.contains(":")) {
            return address;
        }
        try {
            byte[] bytes = InetAddress.getByName(address).getAddress();
            if (bytes.length != 16) {
                return address;
            }
            StringBuilder prefix = new StringBuilder();
            for (int i = 0; i < 8; i += 2) {
                prefix.append(String.format("%02x%02x:", bytes[i], bytes[i + 1]));
            }
            return prefix.append(":/64").toString();
        } catch (UnknownHostException e) {
            return address;
        }
    }
}
