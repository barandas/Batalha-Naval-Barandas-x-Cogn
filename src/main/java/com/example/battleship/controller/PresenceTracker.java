package com.example.battleship.controller;

import com.example.battleship.service.DepartureResult;
import com.example.battleship.service.DisconnectTicket;
import com.example.battleship.service.GameService;
import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

/**
 * Derives player presence from STOMP sessions: a player is online while at least one session is subscribed to
 * their personal topic. When the last one goes away the game service is told, and the disconnection is only
 * escalated after the grace period, so a page reload or a brief network drop is not mistaken for leaving.
 * Count transitions and the matching game service calls happen under one lock, so a reconnect cannot slip in
 * between them.
 */
@Component
public class PresenceTracker {

    private static final Logger log = LoggerFactory.getLogger(PresenceTracker.class);
    private static final Pattern PLAYER_TOPIC = Pattern.compile("^/topic/games/([^/]+)/players/([^/]+)$");

    private record PlayerKey(String gameId, String playerId) {
    }

    private final GameService gameService;
    private final GameUpdatePublisher publisher;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "presence-timeout");
        thread.setDaemon(true);
        return thread;
    });

    /** sessionId -> (subscriptionId -> player). */
    private final Map<String, Map<String, PlayerKey>> subscriptions = new HashMap<>();
    private final Map<PlayerKey, Integer> liveSubscriptions = new HashMap<>();

    public PresenceTracker(GameService gameService, GameUpdatePublisher publisher) {
        this.gameService = gameService;
        this.publisher = publisher;
    }

    @EventListener
    public void onSubscribe(SessionSubscribeEvent event) {
        StompHeaderAccessor headers = StompHeaderAccessor.wrap(event.getMessage());
        String destination = headers.getDestination();
        Matcher matcher = destination == null ? null : PLAYER_TOPIC.matcher(destination);
        if (matcher == null || !matcher.matches() || headers.getSessionId() == null) {
            return;
        }
        PlayerKey key = new PlayerKey(matcher.group(1), matcher.group(2));
        boolean reconnected;
        synchronized (this) {
            subscriptions.computeIfAbsent(headers.getSessionId(), s -> new HashMap<>())
                    .put(String.valueOf(headers.getSubscriptionId()), key);
            reconnected = liveSubscriptions.merge(key, 1, Integer::sum) == 1
                    && gameService.playerConnected(key.gameId(), key.playerId());
        }
        if (reconnected) {
            publish(key.gameId());
        }
    }

    @EventListener
    public void onUnsubscribe(SessionUnsubscribeEvent event) {
        StompHeaderAccessor headers = StompHeaderAccessor.wrap(event.getMessage());
        PlayerKey key;
        synchronized (this) {
            Map<String, PlayerKey> bySubscription = subscriptions.get(headers.getSessionId());
            key = bySubscription == null ? null : bySubscription.remove(String.valueOf(headers.getSubscriptionId()));
        }
        if (key != null) {
            release(key);
        }
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        Map<String, PlayerKey> bySubscription;
        synchronized (this) {
            bySubscription = subscriptions.remove(event.getSessionId());
        }
        if (bySubscription != null) {
            bySubscription.values().forEach(this::release);
        }
    }

    /** Whether some open page is subscribed to the updates of a player of this game. */
    public synchronized boolean isWatched(String gameId) {
        return liveSubscriptions.keySet().stream().anyMatch(k -> k.gameId().equals(gameId));
    }

    /**
     * Runs {@code action} while no subscription can come or go. Locks are always taken presence first, then game
     * (as in {@link #onSubscribe}), so {@code action} may lock games.
     */
    public synchronized <T> T withPresenceFrozen(Supplier<T> action) {
        return action.get();
    }

    private void release(PlayerKey key) {
        Optional<DisconnectTicket> ticket;
        synchronized (this) {
            if (liveSubscriptions.merge(key, -1, Integer::sum) > 0) {
                return;
            }
            liveSubscriptions.remove(key);
            ticket = gameService.playerDisconnected(key.gameId(), key.playerId());
        }
        ticket.ifPresent(t -> {
            publish(key.gameId());
            scheduler.schedule(() -> expire(t), t.gracePeriod().toMillis(), TimeUnit.MILLISECONDS);
        });
    }

    private void expire(DisconnectTicket ticket) {
        try {
            Optional<DepartureResult> result = gameService.expireDisconnection(ticket);
            if (result.isPresent() && result.get() != DepartureResult.CANCELLED) {
                publish(ticket.gameId());
            }
        } catch (RuntimeException e) {
            log.warn("Failed to expire disconnection of player {} in game {}", ticket.playerId(), ticket.gameId(), e);
        }
    }

    private void publish(String gameId) {
        try {
            publisher.publish(gameService.getGame(gameId));
        } catch (RuntimeException e) {
            log.debug("Game {} no longer available for presence update", gameId, e);
        }
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}
