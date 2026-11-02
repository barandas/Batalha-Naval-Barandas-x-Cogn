package com.example.battleship.service;

import java.time.Duration;

/** Issued when a player drops: call {@link GameService#expireDisconnection} after {@code gracePeriod}. */
public record DisconnectTicket(String gameId, String playerId, long connectionEpoch, Duration gracePeriod) {
}
