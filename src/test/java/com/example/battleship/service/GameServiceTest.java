package com.example.battleship.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.battleship.TestFleets;
import com.example.battleship.model.Board;
import com.example.battleship.model.Coordinate;
import com.example.battleship.model.Difficulty;
import com.example.battleship.model.Game;
import com.example.battleship.model.GameMode;
import com.example.battleship.model.GameState;
import com.example.battleship.model.InvalidMoveException;
import com.example.battleship.model.Orientation;
import com.example.battleship.model.Player;
import com.example.battleship.model.ShipPlacement;
import com.example.battleship.model.ShipType;
import com.example.battleship.model.ShotOutcome;
import com.example.battleship.model.ShotResult;
import com.example.battleship.repository.GameRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GameServiceTest {

    @Mock
    private AIService aiService;

    private GameService service;

    @BeforeEach
    void setUp() {
        service = new GameService(new GameRepository(), aiService, new Random(123));
    }

    // ------------------------------------------------------------------ creation / joining

    @Test
    void singlePlayerGameStartsWithReadyComputerOpponent() {
        PlayerSession session = service.createGame("Paulo", GameMode.SINGLE_PLAYER, null);
        Game game = session.game();

        assertThat(game.getState()).isEqualTo(GameState.PLACING_SHIPS);
        assertThat(game.getDifficulty()).isEqualTo(Difficulty.MEDIUM);
        assertThat(game.getPlayers()).hasSize(2);
        Player ai = game.getAiPlayer().orElseThrow();
        assertThat(ai.isReady()).isTrue();
        assertThat(ai.getBoard().isFleetComplete()).isTrue();
        assertThat(session.player().isAi()).isFalse();
    }

    @Test
    void multiplayerGameWaitsForOpponentAndHasRoomCode() {
        Game game = service.createGame("Paulo", GameMode.MULTIPLAYER, Difficulty.HARD).game();

        assertThat(game.getState()).isEqualTo(GameState.WAITING_FOR_OPPONENT);
        assertThat(game.getDifficulty()).isNull();
        assertThat(game.getRoomCode()).hasSize(GameService.ROOM_CODE_LENGTH)
                .matches("[" + GameService.ROOM_CODE_ALPHABET + "]+");
    }

    @Test
    void playerNameIsRequiredAndTrimmed() {
        assertThatThrownBy(() -> service.createGame("   ", GameMode.SINGLE_PLAYER, null))
                .isInstanceOf(InvalidMoveException.class);
        assertThat(service.createGame("  Ana  ", GameMode.MULTIPLAYER, null).player().getName()).isEqualTo("Ana");
    }

    @Test
    void secondPlayerJoinsByRoomCodeIgnoringCase() {
        Game game = service.createGame("Host", GameMode.MULTIPLAYER, null).game();

        PlayerSession guest = service.joinGame(game.getRoomCode().toLowerCase(), "Guest");

        assertThat(guest.game()).isSameAs(game);
        assertThat(game.getPlayers()).extracting(Player::getName).containsExactly("Host", "Guest");
        assertThat(game.getState()).isEqualTo(GameState.PLACING_SHIPS);
    }

    @Test
    void joiningUnknownFullOrSinglePlayerRoomFails() {
        assertThatThrownBy(() -> service.joinGame("NOPE00", "X")).isInstanceOf(GameNotFoundException.class);

        Game multi = service.createGame("Host", GameMode.MULTIPLAYER, null).game();
        service.joinGame(multi.getRoomCode(), "Guest");
        assertThatThrownBy(() -> service.joinGame(multi.getRoomCode(), "Third"))
                .isInstanceOf(InvalidMoveException.class).hasMessageContaining("cheia");

        Game single = service.createGame("Solo", GameMode.SINGLE_PLAYER, Difficulty.EASY).game();
        assertThatThrownBy(() -> service.joinGame(single.getRoomCode(), "Intruder"))
                .isInstanceOf(InvalidMoveException.class);
    }

    // ------------------------------------------------------------------ placement

    @Test
    void randomFleetSuggestionIsValid() {
        List<ShipPlacement> fleet = service.generateRandomFleet();

        assertThat(fleet).extracting(ShipPlacement::type).containsExactlyInAnyOrder(ShipType.values());
        Board board = new Board();
        service.placeFleet(board, fleet);
        assertThat(board.isFleetComplete()).isTrue();
    }

    @Test
    void rejectsIncompleteFleet() {
        Multiplayer mp = newMultiplayerGame();
        List<ShipPlacement> incomplete = TestFleets.stacked().subList(0, 4);

        assertThatThrownBy(() -> service.submitFleet(mp.gameId(), mp.hostId(), incomplete))
                .isInstanceOf(InvalidMoveException.class).hasMessageContaining("exatamente");
    }

    @Test
    void rejectsDuplicatedShipType() {
        Multiplayer mp = newMultiplayerGame();
        List<ShipPlacement> fleet = new ArrayList<>(TestFleets.stacked());
        fleet.set(4, new ShipPlacement(ShipType.CARRIER, Coordinate.of(8, 0), Orientation.HORIZONTAL));

        assertThatThrownBy(() -> service.submitFleet(mp.gameId(), mp.hostId(), fleet))
                .isInstanceOf(InvalidMoveException.class).hasMessageContaining("mais de uma vez");
    }

    @Test
    void rejectsOverlappingOrOutOfBoundsFleetAndLeavesBoardEmpty() {
        Multiplayer mp = newMultiplayerGame();
        List<ShipPlacement> overlapping = new ArrayList<>(TestFleets.stacked());
        overlapping.set(4, new ShipPlacement(ShipType.DESTROYER, Coordinate.of(0, 3), Orientation.VERTICAL));
        List<ShipPlacement> outOfBounds = new ArrayList<>(TestFleets.stacked());
        outOfBounds.set(0, new ShipPlacement(ShipType.CARRIER, Coordinate.of(9, 7), Orientation.HORIZONTAL));

        assertThatThrownBy(() -> service.submitFleet(mp.gameId(), mp.hostId(), overlapping))
                .isInstanceOf(InvalidMoveException.class).hasMessageContaining("sobrepõe");
        assertThatThrownBy(() -> service.submitFleet(mp.gameId(), mp.hostId(), outOfBounds))
                .isInstanceOf(InvalidMoveException.class).hasMessageContaining("limites");

        Player host = service.getGame(mp.gameId()).getPlayer(mp.hostId());
        assertThat(host.getBoard().getShips()).isEmpty();
        assertThat(host.isReady()).isFalse();
    }

    @Test
    void cannotSubmitFleetTwice() {
        Multiplayer mp = newMultiplayerGame();
        service.submitFleet(mp.gameId(), mp.hostId(), TestFleets.stacked());

        assertThatThrownBy(() -> service.submitFleet(mp.gameId(), mp.hostId(), TestFleets.stacked()))
                .isInstanceOf(InvalidMoveException.class);
    }

    @Test
    void battleStartsWhenBothFleetsAreReadyWithHostTurn() {
        Multiplayer mp = readyMultiplayerGame();
        Game game = service.getGame(mp.gameId());

        assertThat(game.getState()).isEqualTo(GameState.IN_PROGRESS);
        assertThat(game.getCurrentTurnPlayerId()).isEqualTo(mp.hostId());
    }

    @Test
    void hostCanPlaceFleetBeforeOpponentJoins() {
        Game game = service.createGame("Host", GameMode.MULTIPLAYER, null).game();
        String hostId = game.getPlayers().get(0).getId();
        service.submitFleet(game.getId(), hostId, TestFleets.stacked());

        assertThat(game.getState()).isEqualTo(GameState.WAITING_FOR_OPPONENT);
        String guestId = service.joinGame(game.getRoomCode(), "Guest").player().getId();
        assertThat(game.getState()).isEqualTo(GameState.PLACING_SHIPS);

        service.submitFleet(game.getId(), guestId, TestFleets.stacked());
        assertThat(game.getState()).isEqualTo(GameState.IN_PROGRESS);
    }

    // ------------------------------------------------------------------ shots and turns

    @Test
    void cannotShootBeforeBattleStarts() {
        Multiplayer mp = newMultiplayerGame();

        assertThatThrownBy(() -> service.fire(mp.gameId(), mp.hostId(), Coordinate.of(0, 0)))
                .isInstanceOf(InvalidMoveException.class).hasMessageContaining("andamento");
    }

    @Test
    void cannotShootOutOfTurn() {
        Multiplayer mp = readyMultiplayerGame();

        assertThatThrownBy(() -> service.fire(mp.gameId(), mp.guestId(), Coordinate.of(0, 0)))
                .isInstanceOf(InvalidMoveException.class).hasMessageContaining("vez");
    }

    @Test
    void turnsAlternateAfterHitsAndMisses() {
        Multiplayer mp = readyMultiplayerGame();
        Game game = service.getGame(mp.gameId());

        ShotOutcome hit = service.fire(mp.gameId(), mp.hostId(), Coordinate.of(0, 0));
        assertThat(hit.result()).isEqualTo(ShotResult.HIT);
        assertThat(game.getCurrentTurnPlayerId()).isEqualTo(mp.guestId());

        ShotOutcome miss = service.fire(mp.gameId(), mp.guestId(), Coordinate.of(9, 9));
        assertThat(miss.result()).isEqualTo(ShotResult.MISS);
        assertThat(game.getCurrentTurnPlayerId()).isEqualTo(mp.hostId());
        assertThat(game.getLastShot().shooterId()).isEqualTo(mp.guestId());
        assertThat(game.getEvents().get(0)).contains("J10").contains("água");
    }

    @Test
    void repeatedShotIsRejectedAndTurnIsKept() {
        Multiplayer mp = readyMultiplayerGame();
        service.fire(mp.gameId(), mp.hostId(), Coordinate.of(9, 9));
        service.fire(mp.gameId(), mp.guestId(), Coordinate.of(9, 9));

        assertThatThrownBy(() -> service.fire(mp.gameId(), mp.hostId(), Coordinate.of(9, 9)))
                .isInstanceOf(InvalidMoveException.class);
        assertThat(service.getGame(mp.gameId()).getCurrentTurnPlayerId()).isEqualTo(mp.hostId());
    }

    @Test
    void sinkingWholeFleetWinsTheGame() {
        Multiplayer mp = readyMultiplayerGame();
        Game game = service.getGame(mp.gameId());
        List<Coordinate> targets = TestFleets.stackedCells();
        int guestCol = 0;

        ShotOutcome last = null;
        for (Coordinate target : targets) {
            last = service.fire(mp.gameId(), mp.hostId(), target);
            if (game.getState() == GameState.FINISHED) {
                break;
            }
            service.fire(mp.gameId(), mp.guestId(), Coordinate.of(5 + guestCol / Board.SIZE, guestCol++ % Board.SIZE)); // guest keeps missing
        }

        assertThat(last).isNotNull();
        assertThat(last.result()).isEqualTo(ShotResult.SUNK);
        assertThat(last.fleetDestroyed()).isTrue();
        assertThat(game.getState()).isEqualTo(GameState.FINISHED);
        assertThat(game.getWinnerId()).isEqualTo(mp.hostId());
        assertThat(game.getCurrentTurnPlayerId()).isNull();
        assertThatThrownBy(() -> service.fire(mp.gameId(), mp.guestId(), Coordinate.of(8, 8)))
                .isInstanceOf(InvalidMoveException.class);
    }

    @Test
    void computerAnswersImmediatelyAfterHumanShot() {
        PlayerSession session = service.createGame("Paulo", GameMode.SINGLE_PLAYER, Difficulty.HARD);
        Game game = session.game();
        String humanId = session.player().getId();
        service.submitFleet(game.getId(), humanId, TestFleets.stacked());
        when(aiService.chooseShot(eq(Difficulty.HARD), any(Board.class))).thenReturn(Coordinate.of(0, 0));

        service.fire(game.getId(), humanId, firstEmptyCell(game.getAiPlayer().orElseThrow().getBoard()));

        Board humanBoard = session.player().getBoard();
        verify(aiService).chooseShot(Difficulty.HARD, humanBoard);
        assertThat(humanBoard.getShots()).containsEntry(Coordinate.of(0, 0), ShotResult.HIT);
        assertThat(game.getCurrentTurnPlayerId()).isEqualTo(humanId);
    }

    @Test
    void computerDoesNotPlayAfterHumanWins() {
        PlayerSession session = service.createGame("Paulo", GameMode.SINGLE_PLAYER, Difficulty.EASY);
        Game game = session.game();
        String humanId = session.player().getId();
        Board aiBoard = game.getAiPlayer().orElseThrow().getBoard();
        service.submitFleet(game.getId(), humanId, TestFleets.stacked());
        List<Coordinate> aiCells = aiBoard.getShips().stream().flatMap(s -> s.getCells().stream()).toList();
        List<Coordinate> aiAnswers = new ArrayList<>();
        for (int i = 0; i < aiCells.size() - 1; i++) {
            aiAnswers.add(Coordinate.of(5 + i / Board.SIZE, i % Board.SIZE)); // rows 5+ are empty in the stacked fleet
        }
        var stubbing = when(aiService.chooseShot(eq(Difficulty.EASY), any(Board.class)));
        for (Coordinate answer : aiAnswers) {
            stubbing = stubbing.thenReturn(answer);
        }

        for (Coordinate cell : aiCells) {
            service.fire(game.getId(), humanId, cell);
        }

        assertThat(game.getWinnerId()).isEqualTo(humanId);
        assertThat(session.player().getBoard().getShots()).hasSize(aiCells.size() - 1);
    }

    @Test
    void fullSinglePlayerGameAgainstRealHardAiEnds() {
        GameService realService = new GameService(new GameRepository(), new AIService(new Random(9)), new Random(9));
        PlayerSession session = realService.createGame("Paulo", GameMode.SINGLE_PLAYER, Difficulty.HARD);
        Game game = session.game();
        String humanId = session.player().getId();
        realService.submitFleet(game.getId(), humanId, TestFleets.stacked());

        for (int row = 0; row < Board.SIZE && game.getState() == GameState.IN_PROGRESS; row++) {
            for (int col = 0; col < Board.SIZE && game.getState() == GameState.IN_PROGRESS; col++) {
                realService.fire(game.getId(), humanId, Coordinate.of(row, col));
            }
        }

        assertThat(game.getState()).isEqualTo(GameState.FINISHED);
        assertThat(game.getWinnerId()).isNotNull();
    }

    // ------------------------------------------------------------------ rematch

    @Test
    void rematchOnlyAfterGameEnds() {
        Multiplayer mp = readyMultiplayerGame();

        assertThatThrownBy(() -> service.requestRematch(mp.gameId(), mp.hostId()))
                .isInstanceOf(InvalidMoveException.class);
    }

    @Test
    void multiplayerRematchNeedsBothPlayersAndAlternatesStarter() {
        Multiplayer mp = readyMultiplayerGame();
        Game game = service.getGame(mp.gameId());
        finishWithHostVictory(mp, game);

        service.requestRematch(mp.gameId(), mp.hostId());
        assertThat(game.getState()).isEqualTo(GameState.FINISHED);

        service.requestRematch(mp.gameId(), mp.guestId());
        assertThat(game.getState()).isEqualTo(GameState.PLACING_SHIPS);
        assertThat(game.getRound()).isEqualTo(2);
        assertThat(game.getWinnerId()).isNull();
        assertThat(game.getPlayers()).allSatisfy(p -> {
            assertThat(p.isReady()).isFalse();
            assertThat(p.getBoard().getShips()).isEmpty();
            assertThat(p.getBoard().getShots()).isEmpty();
        });

        service.submitFleet(mp.gameId(), mp.hostId(), TestFleets.stacked());
        service.submitFleet(mp.gameId(), mp.guestId(), TestFleets.stacked());
        assertThat(game.getCurrentTurnPlayerId()).isEqualTo(mp.guestId());
    }

    @Test
    void singlePlayerRematchIsImmediateAndComputerMayOpen() {
        PlayerSession session = service.createGame("Paulo", GameMode.SINGLE_PLAYER, Difficulty.MEDIUM);
        Game game = session.game();
        String humanId = session.player().getId();
        service.submitFleet(game.getId(), humanId, TestFleets.stacked());
        game.setState(GameState.FINISHED); // shortcut: rematch logic only depends on the finished state

        service.requestRematch(game.getId(), humanId);

        assertThat(game.getState()).isEqualTo(GameState.PLACING_SHIPS);
        Player ai = game.getAiPlayer().orElseThrow();
        assertThat(ai.isReady()).isTrue();
        assertThat(ai.getBoard().isFleetComplete()).isTrue();

        when(aiService.chooseShot(eq(Difficulty.MEDIUM), any(Board.class))).thenReturn(Coordinate.of(9, 9));
        service.submitFleet(game.getId(), humanId, TestFleets.stacked());
        verify(aiService).chooseShot(eq(Difficulty.MEDIUM), any(Board.class));
        assertThat(game.getCurrentTurnPlayerId()).isEqualTo(humanId);
    }

    @Test
    void aiIsNotConsultedInMultiplayer() {
        Multiplayer mp = readyMultiplayerGame();
        service.fire(mp.gameId(), mp.hostId(), Coordinate.of(5, 5));

        verify(aiService, never()).chooseShot(any(), any());
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

    private void finishWithHostVictory(Multiplayer mp, Game game) {
        int guestCol = 0;
        for (Coordinate target : TestFleets.stackedCells()) {
            service.fire(mp.gameId(), mp.hostId(), target);
            if (game.getState() == GameState.FINISHED) {
                return;
            }
            service.fire(mp.gameId(), mp.guestId(), Coordinate.of(5 + guestCol / Board.SIZE, guestCol++ % Board.SIZE));
        }
    }

    private static Coordinate firstEmptyCell(Board board) {
        for (int row = 0; row < Board.SIZE; row++) {
            for (int col = 0; col < Board.SIZE; col++) {
                Coordinate c = Coordinate.of(row, col);
                if (board.shipAt(c).isEmpty()) {
                    return c;
                }
            }
        }
        throw new IllegalStateException();
    }
}
