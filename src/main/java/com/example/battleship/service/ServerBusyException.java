package com.example.battleship.service;

/** The server already holds as many games as it is allowed to keep in memory. */
public class ServerBusyException extends RuntimeException {

    public ServerBusyException(String message) {
        super(message);
    }
}
