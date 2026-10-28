package com.example.battleship.service;

import com.example.battleship.model.Board;
import com.example.battleship.model.Coordinate;
import com.example.battleship.model.Difficulty;
import com.example.battleship.model.Game;
import com.example.battleship.model.GameMode;
import com.example.battleship.model.GameState;
import com.example.battleship.model.InvalidMoveException;
import com.example.battleship.model.LastShot;
import com.example.battleship.model.Player;
import com.example.battleship.model.ShipPlacement;
import com.example.battleship.model.ShipType;
import com.example.battleship.model.ShotOutcome;
import com.example.battleship.repository.GameRepository;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Game rules: room management, fleet placement, alternating turns, victory and rematch. */
@Service
public class GameService {

    static final String ROOM_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final int ROOM_CODE_LENGTH = 6;
    private static final int MAX_NAME_LENGTH = 20;

    private final GameRepository repository;
    private final AIService aiService;
    private final Random random;

    @Autowired
    public GameService(GameRepository repository, AIService aiService) {
        this(repository, aiService, new Random());
    }

    public GameService(GameRepository repository, AIService aiService, Random random) {
        this.repository = repository;
        this.aiService = aiService;
        this.random = random;
    }

    public PlayerSession createGame(String playerName, GameMode mode, Difficulty difficulty) {
        if (mode == null) {
            throw new InvalidMoveException("Modo de jogo é obrigatório.");
        }
        Difficulty effectiveDifficulty = mode == GameMode.SINGLE_PLAYER
                ? (difficulty == null ? Difficulty.MEDIUM : difficulty)
                : null;
        Game game = new Game(generateRoomCode(), mode, effectiveDifficulty);
        Player host = new Player(sanitizeName(playerName), false);
        game.addPlayer(host);

        if (mode == GameMode.SINGLE_PLAYER) {
            Player computer = new Player("Computador (" + difficultyLabel(effectiveDifficulty) + ")", true);
            game.addPlayer(computer);
            prepareAiFleet(computer);
            game.setState(GameState.PLACING_SHIPS);
            game.addEvent("Posicione sua frota para enfrentar o computador.");
        } else {
            game.addEvent("Sala " + game.getRoomCode() + " criada. Aguardando oponente...");
        }
        repository.save(game);
        return new PlayerSession(game, host);
    }

    public PlayerSession joinGame(String roomCode, String playerName) {
        Game game = repository.findByRoomCode(roomCode)
                .orElseThrow(() -> new GameNotFoundException("Sala não encontrada: " + roomCode));
        synchronized (game) {
            if (game.getMode() != GameMode.MULTIPLAYER) {
                throw new InvalidMoveException("Esta sala é de uma partida single player.");
            }
            if (game.isFull()) {
                throw new InvalidMoveException("A sala " + game.getRoomCode() + " já está cheia.");
            }
            Player guest = new Player(sanitizeName(playerName), false);
            game.addPlayer(guest);
            game.setState(GameState.PLACING_SHIPS);
            game.addEvent(guest.getName() + " entrou na sala. Posicionem suas frotas!");
            if (game.allPlayersReady()) {
                startBattle(game);
            }
            return new PlayerSession(game, guest);
        }
    }

    public Game getGame(String gameId) {
        return repository.findById(gameId)
                .orElseThrow(() -> new GameNotFoundException("Partida não encontrada: " + gameId));
    }

    public List<ShipPlacement> generateRandomFleet() {
        Board board = new Board();
        board.placeFleetRandomly(random);
        return board.getShips().stream().map(ShipPlacement::of).toList();
    }

    public Game submitFleet(String gameId, String playerId, List<ShipPlacement> placements) {
        Game game = getGame(gameId);
        synchronized (game) {
            Player player = game.getPlayer(playerId);
            if (game.getState() != GameState.WAITING_FOR_OPPONENT && game.getState() != GameState.PLACING_SHIPS) {
                throw new InvalidMoveException("A fase de posicionamento já terminou.");
            }
            if (player.isReady()) {
                throw new InvalidMoveException("Sua frota já foi confirmada.");
            }
            placeFleet(player.getBoard(), placements);
            player.setReady(true);
            game.addEvent(player.getName() + " posicionou a frota.");
            if (game.allPlayersReady()) {
                startBattle(game);
            }
            return game;
        }
    }

    /** Validates and places a complete fleet atomically: on any error the board is left empty. */
    void placeFleet(Board board, List<ShipPlacement> placements) {
        if (placements == null || placements.size() != ShipType.values().length) {
            throw new InvalidMoveException("A frota deve conter exatamente " + ShipType.values().length + " navios.");
        }
        Set<ShipType> seen = EnumSet.noneOf(ShipType.class);
        for (ShipPlacement placement : placements) {
            if (placement == null || placement.type() == null) {
                throw new InvalidMoveException("Tipo de navio inválido.");
            }
            if (!seen.add(placement.type())) {
                throw new InvalidMoveException(placement.type().getDisplayName() + " aparece mais de uma vez.");
            }
        }
        board.reset();
        try {
            for (ShipPlacement placement : placements) {
                board.placeShip(placement.type(), placement.start(), placement.orientation());
            }
        } catch (InvalidMoveException e) {
            board.reset();
            throw e;
        }
    }

    public ShotOutcome fire(String gameId, String playerId, Coordinate target) {
        Game game = getGame(gameId);
        synchronized (game) {
            Player shooter = game.getPlayer(playerId);
            if (game.getState() != GameState.IN_PROGRESS) {
                throw new InvalidMoveException("A batalha não está em andamento.");
            }
            if (!shooter.getId().equals(game.getCurrentTurnPlayerId())) {
                throw new InvalidMoveException("Não é a sua vez.");
            }
            ShotOutcome outcome = applyShot(game, shooter, target);
            playAiTurnsIfNeeded(game);
            return outcome;
        }
    }

    public Game requestRematch(String gameId, String playerId) {
        Game game = getGame(gameId);
        synchronized (game) {
            Player player = game.getPlayer(playerId);
            if (game.getState() != GameState.FINISHED) {
                throw new InvalidMoveException("A revanche só pode ser pedida ao fim da partida.");
            }
            player.setRematchRequested(true);
            game.getAiPlayer().ifPresent(ai -> ai.setRematchRequested(true));
            boolean everyoneAgreed = game.getPlayers().stream().allMatch(Player::isRematchRequested);
            if (everyoneAgreed) {
                startNewRound(game);
            } else {
                game.addEvent(player.getName() + " pediu revanche.");
            }
            return game;
        }
    }

    private ShotOutcome applyShot(Game game, Player shooter, Coordinate target) {
        Player defender = game.getOpponent(shooter.getId());
        ShotOutcome outcome = defender.getBoard().receiveShot(target);
        game.setLastShot(new LastShot(shooter.getId(), target, outcome.result(), outcome.sunkShip()));
        game.addEvent(describeShot(shooter, outcome));

        if (outcome.fleetDestroyed()) {
            game.setState(GameState.FINISHED);
            game.setWinnerId(shooter.getId());
            game.setCurrentTurnPlayerId(null);
            game.addEvent(shooter.getName() + " afundou toda a frota e venceu a partida!");
        } else {
            game.setCurrentTurnPlayerId(defender.getId());
        }
        return outcome;
    }

    private void playAiTurnsIfNeeded(Game game) {
        while (game.getState() == GameState.IN_PROGRESS) {
            Player current = game.getPlayer(game.getCurrentTurnPlayerId());
            if (!current.isAi()) {
                return;
            }
            Board humanBoard = game.getOpponent(current.getId()).getBoard();
            Coordinate target = aiService.chooseShot(game.getDifficulty(), humanBoard);
            applyShot(game, current, target);
        }
    }

    private void startBattle(Game game) {
        Player first = game.getPlayers().get(game.getStartingPlayerIndex());
        game.setState(GameState.IN_PROGRESS);
        game.setCurrentTurnPlayerId(first.getId());
        game.addEvent("Batalha iniciada! " + first.getName() + " começa.");
        playAiTurnsIfNeeded(game);
    }

    private void startNewRound(Game game) {
        for (Player p : game.getPlayers()) {
            p.resetForNewRound();
            if (p.isAi()) {
                prepareAiFleet(p);
            }
        }
        game.setStartingPlayerIndex((game.getStartingPlayerIndex() + 1) % game.getPlayers().size());
        game.setWinnerId(null);
        game.setLastShot(null);
        game.setCurrentTurnPlayerId(null);
        game.setState(GameState.PLACING_SHIPS);
        game.incrementRound();
        game.clearEvents();
        game.addEvent("Revanche! Rodada " + game.getRound() + ": posicionem suas frotas.");
    }

    private void prepareAiFleet(Player computer) {
        computer.getBoard().placeFleetRandomly(random);
        computer.setReady(true);
    }

    private String describeShot(Player shooter, ShotOutcome outcome) {
        String prefix = shooter.getName() + " atirou em " + outcome.target().label() + ": ";
        return switch (outcome.result()) {
            case MISS -> prefix + "água.";
            case HIT -> prefix + "acertou!";
            case SUNK -> prefix + "afundou o " + outcome.sunkShip().getDisplayName() + "!";
        };
    }

    private String generateRoomCode() {
        String code;
        do {
            StringBuilder builder = new StringBuilder(ROOM_CODE_LENGTH);
            for (int i = 0; i < ROOM_CODE_LENGTH; i++) {
                builder.append(ROOM_CODE_ALPHABET.charAt(random.nextInt(ROOM_CODE_ALPHABET.length())));
            }
            code = builder.toString();
        } while (repository.existsByRoomCode(code));
        return code;
    }

    private static String sanitizeName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new InvalidMoveException("Nome do jogador é obrigatório.");
        }
        return trimmed.length() > MAX_NAME_LENGTH ? trimmed.substring(0, MAX_NAME_LENGTH) : trimmed;
    }

    private static String difficultyLabel(Difficulty difficulty) {
        return switch (difficulty) {
            case EASY -> "Fácil";
            case MEDIUM -> "Médio";
            case HARD -> "Difícil";
        };
    }
}
