package com.example.battleship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.battleship.TestFleets;
import com.example.battleship.dto.GameView;
import com.example.battleship.dto.GameViewMapper;
import com.example.battleship.model.Coordinate;
import com.example.battleship.model.Difficulty;
import com.example.battleship.model.Game;
import com.example.battleship.model.GameEndReason;
import com.example.battleship.model.GameMode;
import com.example.battleship.model.GameState;
import com.example.battleship.model.InvalidMoveException;
import com.example.battleship.repository.GameRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Voluntary exits, temporary drops and persistent disconnections. */
class GameDepartureTest {

    private static final Instant NOW = Instant.parse("2026-01-01T12:00:00Z");
    private static final Duration GRACE = Duration.ofSeconds(60);

    private GameService service;

    @BeforeEach
    void setUp() {
        service = new GameService(new GameRepository(), new AIService(new Random(7)), new Random(123),
                Clock.fixed(NOW, ZoneOffset.UTC), GRACE);
    }

    // ------------------------------------------------------------------ voluntary exit

    @Test
    void leavingSinglePlayerCancelsTheGame() {
        PlayerSession session = service.createGame("Ana", GameMode.SINGLE_PLAYER, Difficulty.EASY);

        assertThat(service.leaveGame(session.game().getId(), session.player().getId()))
                .isEqualTo(DepartureResult.CANCELLED);
        assertThatThrownBy(() -> service.getGame(session.game().getId())).isInstanceOf(GameNotFoundException.class);
    }

    @Test
    void hostLeavingAnEmptyRoomCancelsItAndFreesTheCode() {
        PlayerSession host = service.createGame("Host", GameMode.MULTIPLAYER, null);

        assertThat(service.leaveGame(host.game().getId(), host.player().getId())).isEqualTo(DepartureResult.CANCELLED);
        assertThatThrownBy(() -> service.joinGame(host.game().getRoomCode(), "Late"))
                .isInstanceOf(GameNotFoundException.class);
    }

    @Test
    void guestLeavingDuringPlacementReopensTheSlotAndKeepsHostFleet() {
        Multiplayer mp = newMultiplayerGame();
        Game game = service.getGame(mp.gameId());
        service.submitFleet(mp.gameId(), mp.hostId(), TestFleets.stacked());

        assertThat(service.leaveGame(mp.gameId(), mp.guestId())).isEqualTo(DepartureResult.SLOT_REOPENED);

        assertThat(game.getState()).isEqualTo(GameState.WAITING_FOR_OPPONENT);
        assertThat(game.getPlayers()).hasSize(1);
        assertThat(game.getPlayer(mp.hostId()).isReady()).isTrue();
        assertThat(game.getEvents().get(0)).contains("Guest saiu da partida").contains("Vaga reaberta");
        GameView hostView = GameViewMapper.toView(game, mp.hostId());
        assertThat(hostView.opponentName()).isNull();

        PlayerSession newcomer = service.joinGame(game.getRoomCode(), "Newcomer");
        service.submitFleet(mp.gameId(), newcomer.player().getId(), TestFleets.stacked());
        assertThat(game.getState()).isEqualTo(GameState.IN_PROGRESS);
        assertThat(game.getCurrentTurnPlayerId()).isEqualTo(mp.hostId());
    }

    @Test
    void hostLeavingBeforeBattleLeavesGuestInChargeOfTheRoom() {
        Multiplayer mp = newMultiplayerGame();
        Game game = service.getGame(mp.gameId());

        assertThat(service.leaveGame(mp.gameId(), mp.hostId())).isEqualTo(DepartureResult.SLOT_REOPENED);
        PlayerSession newcomer = service.joinGame(game.getRoomCode(), "Newcomer");
        service.submitFleet(mp.gameId(), mp.guestId(), TestFleets.stacked());
        service.submitFleet(mp.gameId(), newcomer.player().getId(), TestFleets.stacked());

        assertThat(game.getCurrentTurnPlayerId()).isEqualTo(mp.guestId());
    }

    @Test
    void leavingDuringBattleIsAForfeitAndTheOpponentIsNotified() {
        Multiplayer mp = readyMultiplayerGame();
        Game game = service.getGame(mp.gameId());

        assertThat(service.leaveGame(mp.gameId(), mp.hostId())).isEqualTo(DepartureResult.FORFEIT);

        assertThat(game.getState()).isEqualTo(GameState.FINISHED);
        assertThat(game.getWinnerId()).isEqualTo(mp.guestId());
        assertThat(game.getEndReason()).isEqualTo(GameEndReason.OPPONENT_LEFT);
        assertThat(game.getEvents().get(0)).isEqualTo("Host saiu da partida. Guest vence por W.O.");
        GameView guestView = GameViewMapper.toView(game, mp.guestId());
        assertThat(guestView.youWon()).isTrue();
        assertThat(guestView.opponentLeft()).isTrue();
        assertThat(guestView.endReason()).isEqualTo(GameEndReason.OPPONENT_LEFT);

        assertThatThrownBy(() -> service.leaveGame(mp.gameId(), mp.hostId()))
                .isInstanceOf(InvalidMoveException.class).hasMessage("Você já saiu desta partida.");
        assertThatThrownBy(() -> service.requestRematch(mp.gameId(), mp.hostId()))
                .isInstanceOf(InvalidMoveException.class);
        assertThatThrownBy(() -> service.joinGame(game.getRoomCode(), "Late"))
                .isInstanceOf(InvalidMoveException.class);
    }

    @Test
    void winnerByForfeitCanReopenTheSlotForANewOpponent() {
        Multiplayer mp = readyMultiplayerGame();
        Game game = service.getGame(mp.gameId());
        service.fire(mp.gameId(), mp.hostId(), Coordinate.of(0, 0));
        service.leaveGame(mp.gameId(), mp.hostId());

        service.requestRematch(mp.gameId(), mp.guestId());

        assertThat(game.getState()).isEqualTo(GameState.WAITING_FOR_OPPONENT);
        assertThat(game.getRound()).isEqualTo(2);
        assertThat(game.getPlayers()).extracting(p -> p.getId()).containsExactly(mp.guestId());
        assertThat(game.getPlayer(mp.guestId()).getBoard().getShips()).isEmpty();
        assertThat(game.getEndReason()).isNull();
        assertThat(service.joinGame(game.getRoomCode(), "Newcomer").game()).isSameAs(game);
    }

    @Test
    void lastPlayerLeavingAfterForfeitCancelsTheRoom() {
        Multiplayer mp = readyMultiplayerGame();
        service.leaveGame(mp.gameId(), mp.hostId());

        assertThat(service.leaveGame(mp.gameId(), mp.guestId())).isEqualTo(DepartureResult.CANCELLED);
    }

    @Test
    void leavingAfterANormalEndKeepsTheResultUntilTheWinnerReopensTheSlot() {
        Multiplayer mp = readyMultiplayerGame();
        Game game = service.getGame(mp.gameId());
        int miss = 0;
        for (Coordinate target : TestFleets.stackedCells()) {
            service.fire(mp.gameId(), mp.hostId(), target);
            if (game.getState() == GameState.FINISHED) {
                break;
            }
            service.fire(mp.gameId(), mp.guestId(), Coordinate.of(5 + miss / 10, miss++ % 10));
        }
        assertThat(game.getEndReason()).isEqualTo(GameEndReason.FLEET_DESTROYED);
        service.requestRematch(mp.gameId(), mp.hostId());

        assertThat(service.leaveGame(mp.gameId(), mp.guestId())).isEqualTo(DepartureResult.RESULT_KEPT);
        assertThat(game.getState()).isEqualTo(GameState.FINISHED);
        assertThat(game.getWinnerId()).isEqualTo(mp.hostId());
        assertThat(game.getEndReason()).isEqualTo(GameEndReason.FLEET_DESTROYED);
        assertThat(game.getPlayer(mp.hostId()).isRematchRequested()).isFalse();
        assertThat(GameViewMapper.toView(game, mp.hostId()).opponentLeft()).isTrue();

        service.requestRematch(mp.gameId(), mp.hostId());
        assertThat(game.getState()).isEqualTo(GameState.WAITING_FOR_OPPONENT);
        assertThat(game.getPlayers()).hasSize(1);
    }

    @Test
    void persistentDisconnectionAfterTheFleetWasSunkKeepsTheVictory() {
        Multiplayer mp = readyMultiplayerGame();
        Game game = service.getGame(mp.gameId());
        DisconnectTicket ticket = service.playerDisconnected(mp.gameId(), mp.guestId()).orElseThrow();
        for (Coordinate target : TestFleets.stackedCells()) {
            service.fire(mp.gameId(), mp.hostId(), target);
            if (game.getState() == GameState.FINISHED) {
                break;
            }
            game.setCurrentTurnPlayerId(mp.hostId());
        }

        assertThat(service.expireDisconnection(ticket)).contains(DepartureResult.RESULT_KEPT);
        assertThat(game.getState()).isEqualTo(GameState.FINISHED);
        assertThat(game.getWinnerId()).isEqualTo(mp.hostId());
        assertThat(game.getEndReason()).isEqualTo(GameEndReason.FLEET_DESTROYED);
    }

    @Test
    void joiningARoomCancelledWhileWaitingForItsLockFails() {
        Game cancelled = new Game("ABC123", GameMode.MULTIPLAYER, null);
        GameRepository staleLookup = new GameRepository() {
            @Override
            public Optional<Game> findByRoomCode(String roomCode) {
                return Optional.of(cancelled);
            }
        };
        GameService joining = new GameService(staleLookup, new AIService(), new Random(1));

        assertThatThrownBy(() -> joining.joinGame("ABC123", "Late")).isInstanceOf(GameNotFoundException.class);
        assertThat(cancelled.getPlayers()).isEmpty();
    }

    // ------------------------------------------------------------------ disconnections

    @Test
    void dropDuringBattleOnlyWarnsTheOpponent() {
        Multiplayer mp = readyMultiplayerGame();
        Game game = service.getGame(mp.gameId());

        Optional<DisconnectTicket> ticket = service.playerDisconnected(mp.gameId(), mp.guestId());

        assertThat(ticket).isPresent();
        assertThat(ticket.get().gracePeriod()).isEqualTo(GRACE);
        assertThat(game.getState()).isEqualTo(GameState.IN_PROGRESS);
        assertThat(game.getEvents().get(0)).isEqualTo("Guest perdeu a conexão. Aguardando reconexão por 60 s...");
        GameView hostView = GameViewMapper.toView(game, mp.hostId());
        assertThat(hostView.opponentConnected()).isFalse();
        assertThat(hostView.opponentLeft()).isFalse();
        assertThat(hostView.opponentReconnectDeadline()).isEqualTo(NOW.plus(GRACE).toEpochMilli());
        assertThat(service.playerDisconnected(mp.gameId(), mp.guestId())).isEmpty();
    }

    @Test
    void reconnectingWithinTheGracePeriodKeepsTheGame() {
        Multiplayer mp = readyMultiplayerGame();
        Game game = service.getGame(mp.gameId());
        DisconnectTicket ticket = service.playerDisconnected(mp.gameId(), mp.guestId()).orElseThrow();

        assertThat(service.playerConnected(mp.gameId(), mp.guestId())).isTrue();
        assertThat(service.expireDisconnection(ticket)).isEmpty();

        assertThat(game.getState()).isEqualTo(GameState.IN_PROGRESS);
        assertThat(game.getEvents().get(0)).isEqualTo("Guest reconectou.");
        GameView hostView = GameViewMapper.toView(game, mp.hostId());
        assertThat(hostView.opponentConnected()).isTrue();
        assertThat(hostView.opponentReconnectDeadline()).isNull();
    }

    @Test
    void staleTicketFromAnEarlierDropIsIgnored() {
        Multiplayer mp = readyMultiplayerGame();
        Game game = service.getGame(mp.gameId());
        DisconnectTicket first = service.playerDisconnected(mp.gameId(), mp.guestId()).orElseThrow();
        service.playerConnected(mp.gameId(), mp.guestId());
        DisconnectTicket second = service.playerDisconnected(mp.gameId(), mp.guestId()).orElseThrow();

        assertThat(service.expireDisconnection(first)).isEmpty();
        assertThat(game.getState()).isEqualTo(GameState.IN_PROGRESS);
        assertThat(service.expireDisconnection(second)).contains(DepartureResult.FORFEIT);
    }

    @Test
    void persistentDisconnectionDuringBattleIsAForfeitWithItsOwnReason() {
        Multiplayer mp = readyMultiplayerGame();
        Game game = service.getGame(mp.gameId());
        DisconnectTicket ticket = service.playerDisconnected(mp.gameId(), mp.guestId()).orElseThrow();

        assertThat(service.expireDisconnection(ticket)).contains(DepartureResult.FORFEIT);

        assertThat(game.getWinnerId()).isEqualTo(mp.hostId());
        assertThat(game.getEndReason()).isEqualTo(GameEndReason.OPPONENT_DISCONNECTED);
        assertThat(game.getEvents().get(0)).isEqualTo("Guest perdeu a conexão e não voltou. Host vence por W.O.");
        assertThat(service.playerConnected(mp.gameId(), mp.guestId())).isFalse();
    }

    @Test
    void persistentDisconnectionBeforeBattleReopensTheSlot() {
        Multiplayer mp = newMultiplayerGame();
        Game game = service.getGame(mp.gameId());
        DisconnectTicket ticket = service.playerDisconnected(mp.gameId(), mp.guestId()).orElseThrow();

        assertThat(service.expireDisconnection(ticket)).contains(DepartureResult.SLOT_REOPENED);
        assertThat(game.getState()).isEqualTo(GameState.WAITING_FOR_OPPONENT);
        assertThat(game.getEvents().get(0)).startsWith("Guest perdeu a conexão e não voltou. Vaga reaberta");
    }

    @Test
    void persistentDisconnectionOfALonelyHostCancelsTheRoom() {
        PlayerSession host = service.createGame("Host", GameMode.MULTIPLAYER, null);
        DisconnectTicket ticket = service.playerDisconnected(host.game().getId(), host.player().getId()).orElseThrow();

        assertThat(service.expireDisconnection(ticket)).contains(DepartureResult.CANCELLED);
        assertThat(service.expireDisconnection(ticket)).isEmpty();
    }

    @Test
    void disconnectionsAreIgnoredForSinglePlayerAndUnknownPlayers() {
        PlayerSession single = service.createGame("Ana", GameMode.SINGLE_PLAYER, Difficulty.EASY);
        Multiplayer mp = newMultiplayerGame();
        service.leaveGame(mp.gameId(), mp.guestId());

        assertThat(service.playerDisconnected(single.game().getId(), single.player().getId())).isEmpty();
        assertThat(service.playerDisconnected(mp.gameId(), mp.guestId())).isEmpty();
        assertThat(service.playerDisconnected("missing", "nobody")).isEmpty();
        assertThat(service.playerConnected("missing", "nobody")).isFalse();
    }

    // ------------------------------------------------------------------ helpers

    private record Multiplayer(String gameId, String hostId, String guestId) {
    }

    private Multiplayer newMultiplayerGame() {
        PlayerSession host = service.createGame("Host", GameMode.MULTIPLAYER, null);
        PlayerSession guest = service.joinGame(host.game().getRoomCode(), "Guest");
        return new Multiplayer(host.game().getId(), host.player().getId(), guest.player().getId());
    }

    private Multiplayer readyMultiplayerGame() {
        Multiplayer mp = newMultiplayerGame();
        service.submitFleet(mp.gameId(), mp.hostId(), TestFleets.stacked());
        service.submitFleet(mp.gameId(), mp.guestId(), TestFleets.stacked());
        return mp;
    }
}
