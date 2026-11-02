package com.example.battleship.model;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class Game {

    public static final int MAX_PLAYERS = 2;
    private static final int MAX_EVENTS = 30;

    private final String id = UUID.randomUUID().toString();
    private final String roomCode;
    private final GameMode mode;
    private final Difficulty difficulty;
    private final Instant createdAt = Instant.now();
    private final List<Player> players = new ArrayList<>(MAX_PLAYERS);
    private final Deque<String> events = new ArrayDeque<>();

    private GameState state = GameState.WAITING_FOR_OPPONENT;
    private String currentTurnPlayerId;
    private String winnerId;
    private int startingPlayerIndex;
    private int round = 1;
    private LastShot lastShot;

    public Game(String roomCode, GameMode mode, Difficulty difficulty) {
        this.roomCode = roomCode;
        this.mode = mode;
        this.difficulty = difficulty;
    }

    public void addPlayer(Player player) {
        if (isFull()) {
            throw new InvalidMoveException("A sala " + roomCode + " já está cheia.");
        }
        players.add(player);
    }

    public boolean isFull() {
        return players.size() >= MAX_PLAYERS;
    }

    public Optional<Player> findPlayer(String playerId) {
        return players.stream().filter(p -> p.getId().equals(playerId)).findFirst();
    }

    public Player getPlayer(String playerId) {
        return findPlayer(playerId)
                .orElseThrow(() -> new InvalidMoveException("Jogador não pertence a esta partida."));
    }

    public Optional<Player> findOpponent(String playerId) {
        return players.stream().filter(p -> !p.getId().equals(playerId)).findFirst();
    }

    public Player getOpponent(String playerId) {
        return findOpponent(playerId).orElseThrow(() -> new InvalidMoveException("Aguardando oponente."));
    }

    public Optional<Player> getAiPlayer() {
        return players.stream().filter(Player::isAi).findFirst();
    }

    public boolean allPlayersReady() {
        return isFull() && players.stream().allMatch(Player::isReady);
    }

    public void addEvent(String event) {
        events.addFirst(event);
        while (events.size() > MAX_EVENTS) {
            events.removeLast();
        }
    }

    public void clearEvents() {
        events.clear();
    }

    public String getId() {
        return id;
    }

    public String getRoomCode() {
        return roomCode;
    }

    public GameMode getMode() {
        return mode;
    }

    public Difficulty getDifficulty() {
        return difficulty;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<Player> getPlayers() {
        return Collections.unmodifiableList(players);
    }

    /** Most recent events first. */
    public List<String> getEvents() {
        return List.copyOf(events);
    }

    public GameState getState() {
        return state;
    }

    public void setState(GameState state) {
        this.state = state;
    }

    public String getCurrentTurnPlayerId() {
        return currentTurnPlayerId;
    }

    public void setCurrentTurnPlayerId(String currentTurnPlayerId) {
        this.currentTurnPlayerId = currentTurnPlayerId;
    }

    public String getWinnerId() {
        return winnerId;
    }

    public void setWinnerId(String winnerId) {
        this.winnerId = winnerId;
    }

    public int getStartingPlayerIndex() {
        return startingPlayerIndex;
    }

    public void setStartingPlayerIndex(int startingPlayerIndex) {
        this.startingPlayerIndex = startingPlayerIndex;
    }

    public int getRound() {
        return round;
    }

    public void incrementRound() {
        round++;
    }

    public LastShot getLastShot() {
        return lastShot;
    }

    public void setLastShot(LastShot lastShot) {
        this.lastShot = lastShot;
    }
}
