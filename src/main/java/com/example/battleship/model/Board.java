package com.example.battleship.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/** A 10x10 grid holding one fleet and the shots received from the opponent. */
public class Board {

    public static final int SIZE = 10;
    private static final int MAX_RANDOM_ATTEMPTS = 1_000;

    private final Map<ShipType, Ship> ships = new EnumMap<>(ShipType.class);
    private final Map<Coordinate, ShotResult> shots = new LinkedHashMap<>();

    public boolean canPlace(ShipType type, Coordinate start, Orientation orientation) {
        return placementError(type, start, orientation).isEmpty();
    }

    public Ship placeShip(ShipType type, Coordinate start, Orientation orientation) {
        placementError(type, start, orientation).ifPresent(error -> {
            throw new InvalidMoveException(error);
        });
        Ship ship = new Ship(type, start, orientation);
        ships.put(type, ship);
        return ship;
    }

    private Optional<String> placementError(ShipType type, Coordinate start, Orientation orientation) {
        if (type == null || start == null || orientation == null) {
            return Optional.of("Tipo, posição e orientação do navio são obrigatórios.");
        }
        if (!shots.isEmpty()) {
            return Optional.of("Não é possível reposicionar navios após o início da batalha.");
        }
        if (ships.containsKey(type)) {
            return Optional.of(type.getDisplayName() + " já foi posicionado.");
        }
        for (Coordinate cell : Ship.computeCells(type, start, orientation)) {
            if (!cell.isWithinBoard()) {
                return Optional.of(type.getDisplayName() + " ultrapassa os limites do tabuleiro.");
            }
            if (shipAt(cell).isPresent()) {
                return Optional.of(type.getDisplayName() + " sobrepõe outro navio em " + cell.label() + ".");
            }
        }
        return Optional.empty();
    }

    public void removeShip(ShipType type) {
        ships.remove(type);
    }

    /** Removes every ship and shot, returning the board to its initial state. */
    public void reset() {
        ships.clear();
        shots.clear();
    }

    public void placeFleetRandomly(Random random) {
        reset();
        ShipType[] types = ShipType.values();
        for (ShipType type : types) {
            boolean placed = false;
            for (int attempt = 0; attempt < MAX_RANDOM_ATTEMPTS && !placed; attempt++) {
                Orientation orientation = random.nextBoolean() ? Orientation.HORIZONTAL : Orientation.VERTICAL;
                Coordinate start = Coordinate.of(random.nextInt(SIZE), random.nextInt(SIZE));
                if (canPlace(type, start, orientation)) {
                    placeShip(type, start, orientation);
                    placed = true;
                }
            }
            if (!placed) {
                // Extremely unlikely with a standard fleet; start over to avoid a dead end.
                placeFleetRandomly(random);
                return;
            }
        }
    }

    public boolean isFleetComplete() {
        return ships.size() == ShipType.values().length;
    }

    public ShotOutcome receiveShot(Coordinate target) {
        if (target == null || !target.isWithinBoard()) {
            throw new InvalidMoveException("Coordenada fora do tabuleiro.");
        }
        if (shots.containsKey(target)) {
            throw new InvalidMoveException("Você já atirou em " + target.label() + ".");
        }
        Optional<Ship> hitShip = shipAt(target);
        if (hitShip.isEmpty()) {
            shots.put(target, ShotResult.MISS);
            return new ShotOutcome(target, ShotResult.MISS, null, false);
        }
        Ship ship = hitShip.get();
        ship.registerHit(target);
        if (ship.isSunk()) {
            shots.put(target, ShotResult.SUNK);
            return new ShotOutcome(target, ShotResult.SUNK, ship.getType(), allShipsSunk());
        }
        shots.put(target, ShotResult.HIT);
        return new ShotOutcome(target, ShotResult.HIT, null, false);
    }

    public Optional<Ship> shipAt(Coordinate coordinate) {
        return ships.values().stream().filter(ship -> ship.occupies(coordinate)).findFirst();
    }

    public boolean hasBeenShot(Coordinate coordinate) {
        return shots.containsKey(coordinate);
    }

    public boolean allShipsSunk() {
        return isFleetComplete() && ships.values().stream().allMatch(Ship::isSunk);
    }

    public List<Ship> getShips() {
        return List.copyOf(ships.values());
    }

    public Map<Coordinate, ShotResult> getShots() {
        return Collections.unmodifiableMap(shots);
    }

    public List<Ship> getSunkShips() {
        return ships.values().stream().filter(Ship::isSunk).toList();
    }

    public List<ShipType> getRemainingShipTypes() {
        List<ShipType> remaining = new ArrayList<>();
        for (ShipType type : ShipType.values()) {
            Ship ship = ships.get(type);
            if (ship == null || !ship.isSunk()) {
                remaining.add(type);
            }
        }
        return remaining;
    }

    /** Cells that were hit but belong to ships that are still afloat (public information for the shooter). */
    public Set<Coordinate> getUnresolvedHits() {
        Set<Coordinate> sunkCells = new HashSet<>();
        getSunkShips().forEach(ship -> sunkCells.addAll(ship.getCells()));
        Set<Coordinate> unresolved = new HashSet<>();
        shots.forEach((coordinate, result) -> {
            if (result != ShotResult.MISS && !sunkCells.contains(coordinate)) {
                unresolved.add(coordinate);
            }
        });
        return unresolved;
    }
}
