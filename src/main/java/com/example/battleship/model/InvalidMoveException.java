package com.example.battleship.model;

/** Thrown when a move breaks a game rule (bad placement, repeated shot, wrong turn, ...). */
public class InvalidMoveException extends RuntimeException {

    public InvalidMoveException(String message) {
        super(message);
    }
}
