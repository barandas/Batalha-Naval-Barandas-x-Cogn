package com.example.battleship.model;

import java.util.UUID;

public class Player {

    private final String id;
    private final String name;
    private final boolean ai;
    private final Board board = new Board();
    private boolean ready;
    private boolean rematchRequested;

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

    public void resetForNewRound() {
        board.reset();
        ready = false;
        rematchRequested = false;
    }
}
