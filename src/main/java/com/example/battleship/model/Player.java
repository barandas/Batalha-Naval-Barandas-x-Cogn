package com.example.battleship.model;

import java.time.Instant;
import java.util.UUID;

public class Player {

    private final String id;
    private final String name;
    private final boolean ai;
    private final Board board = new Board();
    private boolean ready;
    private boolean rematchRequested;
    private boolean connected = true;
    private boolean departed;
    private Instant reconnectDeadline;
    private long connectionEpoch;

    public Player(String name, boolean ai) {
        this(UUID.randomUUID().toString(), name, ai);
    }

    public Player(String id, String name, boolean ai) {
        this.id = id;
        this.name = name;
        this.ai = ai;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public boolean isAi() {
        return ai;
    }

    public Board getBoard() {
        return board;
    }

    public boolean isReady() {
        return ready;
    }

    public void setReady(boolean ready) {
        this.ready = ready;
    }

    public boolean isRematchRequested() {
        return rematchRequested;
    }

    public void setRematchRequested(boolean rematchRequested) {
        this.rematchRequested = rematchRequested;
    }

    public boolean isConnected() {
        return connected;
    }

    /** Deadline for a disconnected player to come back; {@code null} while connected. */
    public Instant getReconnectDeadline() {
        return reconnectDeadline;
    }

    /** Incremented on every connection change, so stale disconnection timers can be recognised. */
    public long getConnectionEpoch() {
        return connectionEpoch;
    }

    public void markDisconnected(Instant deadline) {
        connected = false;
        reconnectDeadline = deadline;
        connectionEpoch++;
    }

    public void markConnected() {
        connected = true;
        reconnectDeadline = null;
        connectionEpoch++;
    }

    /** The player is no longer taking part (kept only so the final boards can still be shown). */
    public boolean isDeparted() {
        return departed;
    }

    public void markDeparted() {
        departed = true;
        connected = false;
        reconnectDeadline = null;
        connectionEpoch++;
    }

    public void resetForNewRound() {
        board.reset();
        ready = false;
        rematchRequested = false;
    }
}
