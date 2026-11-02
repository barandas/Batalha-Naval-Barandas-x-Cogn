package com.example.battleship.repository;

import com.example.battleship.model.Game;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/** Thread-safe in-memory storage for active games. */
@Repository
public class GameRepository {

    private final Map<String, Game> gamesById = new ConcurrentHashMap<>();
    private final Map<String, String> gameIdsByRoomCode = new ConcurrentHashMap<>();

    public Game save(Game game) {
        gamesById.put(game.getId(), game);
        gameIdsByRoomCode.put(normalize(game.getRoomCode()), game.getId());
        return game;
    }

    public Optional<Game> findById(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(gamesById.get(id));
    }

    public Optional<Game> findByRoomCode(String roomCode) {
        if (roomCode == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(gameIdsByRoomCode.get(normalize(roomCode))).flatMap(this::findById);
    }

    public boolean existsByRoomCode(String roomCode) {
        return gameIdsByRoomCode.containsKey(normalize(roomCode));
    }

    public Collection<Game> findAll() {
        return gamesById.values();
    }

    public void delete(Game game) {
        gamesById.remove(game.getId());
        gameIdsByRoomCode.remove(normalize(game.getRoomCode()));
    }

    private static String normalize(String roomCode) {
        return roomCode.trim().toUpperCase(Locale.ROOT);
    }
}
