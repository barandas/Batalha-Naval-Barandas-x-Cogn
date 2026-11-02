package com.example.battleship.service;

import com.example.battleship.model.Board;
import com.example.battleship.model.Coordinate;
import com.example.battleship.model.Difficulty;
import com.example.battleship.model.Game;
import com.example.battleship.model.GameEndReason;
import com.example.battleship.model.GameMode;
import com.example.battleship.model.GameState;
import com.example.battleship.model.InvalidMoveException;
import com.example.battleship.model.LastShot;
import com.example.battleship.model.Player;
import com.example.battleship.model.ShipPlacement;
import com.example.battleship.model.ShipType;
import com.example.battleship.model.ShotOutcome;
import com.example.battleship.repository.GameRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Game rules: room management, fleet placement, alternating turns, victory and rematch, plus what happens when a
 * participant leaves. A voluntary exit ({@link #leaveGame}) takes effect immediately; a dropped connection
 * ({@link #playerDisconnected}) only counts as leaving if it is not recovered within the grace period
 * ({@link #expireDisconnection}). Either way, before the battle the slot is reopened, during the battle the remaining
 * player wins by forfeit and after the end the result is kept; a game without any other human is cancelled.
 */
@Service
public class GameService {

    static final String ROOM_CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final int ROOM_CODE_LENGTH = 6;
    private static final int MAX_NAME_LENGTH = 20;
    public static final Duration DEFAULT_RECONNECT_GRACE = Duration.ofSeconds(60);

    private final GameRepository repository;
    private final AIService aiService;
    private final Random random;
    private final Clock clock;
    private final Duration reconnectGrace;

    @Autowired
    public GameService(GameRepository repository, AIService aiService,
                       @Value("${battleship.reconnect-grace:60s}") Duration reconnectGrace) {
        this(repository, aiService, new SecureRandom(), Clock.systemUTC(), reconnectGrace);
    }

    public GameService(GameRepository repository, AIService aiService, Random random) {
        this(repository, aiService, random, Clock.systemUTC(), DEFAULT_RECONNECT_GRACE);
    }

    public GameService(GameRepository repository, AIService aiService, Random random, Clock clock,
                       Duration reconnectGrace) {
        this.repository = repository;
        this.aiService = aiService;
        this.random = random;
        this.clock = clock;
        this.reconnectGrace = reconnectGrace;
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
            if (repository.findById(game.getId()).isEmpty()) {
                throw new GameNotFoundException("Sala não encontrada: " + roomCode);
            }
            if (game.getMode() != GameMode.MULTIPLAYER) {
                throw new InvalidMoveException("Esta sala é de uma partida single player.");
            }
            if (game.isFull() || game.getState() != GameState.WAITING_FOR_OPPONENT) {
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

    public boolean isHumanPlayer(String gameId, String playerId) {
        return repository.findById(gameId)
                .map(game -> {
                    synchronized (game) {
                        return game.findPlayer(playerId).filter(p -> !p.isAi() && !p.isDeparted()).isPresent();
                    }
                })
                .orElse(false);
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
            Player player = requireActive(game, playerId);
            if (game.getState() != GameState.FINISHED) {
                throw new InvalidMoveException("A revanche só pode ser pedida ao fim da partida.");
            }
            Optional<Player> departed = game.findOpponent(playerId).filter(Player::isDeparted);
            if (departed.isPresent()) {
                game.removePlayer(departed.get());
                reopenSlot(game, player);
                game.addEvent(player.getName() + " reabriu a vaga. Compartilhe o código " + game.getRoomCode() + ".");
                return game;
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

    /**
     * Removes games nobody has acted on since {@code idleTimeout} and that no open page is watching, so finished
     * or abandoned games do not pile up in memory. {@code watched} is called while holding a game's lock, so it must
     * not wait for a lock that is held while locking a game.
     *
     * @return how many games were removed
     */
    public int evictIdleGames(Duration idleTimeout, Predicate<String> watched) {
        Instant cutoff = clock.instant().minus(idleTimeout);
        int evicted = 0;
        for (Game game : List.copyOf(repository.findAll())) {
            synchronized (game) {
                if (game.getLastActivity().isBefore(cutoff) && !watched.test(game.getId())
                        && repository.findById(game.getId()).isPresent()) {
                    repository.delete(game);
                    evicted++;
                }
            }
        }
        return evicted;
    }

    /** Voluntary exit. Takes effect immediately; see the class documentation for the outcome. */
    public DepartureResult leaveGame(String gameId, String playerId) {
        Game game = getGame(gameId);
        synchronized (game) {
            Player player = requireActive(game, playerId);
            return removeParticipant(game, player, false);
        }
    }

    /**
     * Records that a player's last live connection dropped. Nothing is decided yet: the opponent is told and the
     * returned ticket must be passed to {@link #expireDisconnection} once its grace period is over.
     */
    public Optional<DisconnectTicket> playerDisconnected(String gameId, String playerId) {
        Optional<Game> found = repository.findById(gameId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Game game = found.get();
        synchronized (game) {
            Optional<Player> player = game.findPlayer(playerId);
            if (game.getMode() != GameMode.MULTIPLAYER || player.isEmpty() || player.get().isAi()
                    || player.get().isDeparted() || !player.get().isConnected()) {
                return Optional.empty();
            }
            Player p = player.get();
            p.markDisconnected(clock.instant().plus(reconnectGrace));
            game.addEvent(p.getName() + " perdeu a conexão. Aguardando reconexão por "
                    + reconnectGrace.toSeconds() + " s...");
            return Optional.of(new DisconnectTicket(gameId, playerId, p.getConnectionEpoch(), reconnectGrace));
        }
    }

    /** @return whether the player was disconnected and is now back (the opponent should be told). */
    public boolean playerConnected(String gameId, String playerId) {
        Optional<Game> found = repository.findById(gameId);
        if (found.isEmpty()) {
            return false;
        }
        Game game = found.get();
        synchronized (game) {
            Optional<Player> player = game.findPlayer(playerId);
            if (player.isEmpty() || player.get().isDeparted() || player.get().isConnected()) {
                return false;
            }
            player.get().markConnected();
            game.addEvent(player.get().getName() + " reconectou.");
            return true;
        }
    }

    /**
     * Grace period over: if the player has not reconnected since the ticket was issued, the disconnection is
     * treated as persistent and the player leaves the game.
     */
    public Optional<DepartureResult> expireDisconnection(DisconnectTicket ticket) {
        Optional<Game> found = repository.findById(ticket.gameId());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Game game = found.get();
        synchronized (game) {
            Optional<Player> player = game.findPlayer(ticket.playerId())
                    .filter(p -> !p.isDeparted() && !p.isConnected())
                    .filter(p -> p.getConnectionEpoch() == ticket.connectionEpoch());
            return player.map(p -> removeParticipant(game, p, true));
        }
    }

    private DepartureResult removeParticipant(Game game, Player player, boolean disconnected) {
        Optional<Player> other = game.findOpponent(player.getId()).filter(p -> !p.isAi() && !p.isDeparted());
        if (other.isEmpty()) {
            repository.delete(game);
            return DepartureResult.CANCELLED;
        }
        Player remaining = other.get();
        String what = player.getName() + (disconnected ? " perdeu a conexão e não voltou" : " saiu da partida");
        if (game.getState() == GameState.IN_PROGRESS) {
            player.markDeparted();
            game.setState(GameState.FINISHED);
            game.setWinnerId(remaining.getId());
            game.setCurrentTurnPlayerId(null);
            game.setEndReason(disconnected ? GameEndReason.OPPONENT_DISCONNECTED : GameEndReason.OPPONENT_LEFT);
            game.addEvent(what + ". " + remaining.getName() + " vence por W.O.");
            return DepartureResult.FORFEIT;
        }
        if (game.getState() == GameState.FINISHED) {
            player.markDeparted();
            remaining.setRematchRequested(false);
            game.addEvent(what + ". " + remaining.getName() + " pode aguardar um novo oponente.");
            return DepartureResult.RESULT_KEPT;
        }
        game.removePlayer(player);
        reopenSlot(game, remaining);
        game.addEvent(what + ". Vaga reaberta: compartilhe o código " + game.getRoomCode() + ".");
        return DepartureResult.SLOT_REOPENED;
    }

    /** Leaves {@code remaining} alone in the room, waiting for a new opponent. A fleet placed this round is kept. */
    private void reopenSlot(Game game, Player remaining) {
        if (game.getState() == GameState.FINISHED) {
            remaining.resetForNewRound();
            game.incrementRound();
            game.setWinnerId(null);
            game.setLastShot(null);
            game.setEndReason(null);
        }
        remaining.setRematchRequested(false);
        game.setCurrentTurnPlayerId(null);
        game.setStartingPlayerIndex(0);
        game.setState(GameState.WAITING_FOR_OPPONENT);
    }

    private static Player requireActive(Game game, String playerId) {
        Player player = game.getPlayer(playerId);
        if (player.isDeparted()) {
            throw new InvalidMoveException("Você já saiu desta partida.");
        }
        return player;
    }

    private ShotOutcome applyShot(Game game, Player shooter, Coordinate target) {
        Player defender = game.getOpponent(shooter.getId());
        ShotOutcome outcome = defender.getBoard().receiveShot(target);
        game.setLastShot(new LastShot(shooter.getId(), target, outcome.result(), outcome.sunkShip()));
        game.addEvent(describeShot(shooter, outcome));

        if (outcome.fleetDestroyed()) {
            game.setState(GameState.FINISHED);
            game.setWinnerId(shooter.getId());
            game.setEndReason(GameEndReason.FLEET_DESTROYED);
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
        game.setEndReason(null);
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
