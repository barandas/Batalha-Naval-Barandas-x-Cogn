package com.example.battleship.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.battleship.model.Coordinate;
import com.example.battleship.model.Difficulty;
import com.example.battleship.model.Game;
import com.example.battleship.model.GameMode;
import com.example.battleship.model.InvalidMoveException;
import com.example.battleship.model.Player;
import com.example.battleship.service.GameNotFoundException;
import com.example.battleship.service.GameService;
import com.example.battleship.service.PlayerSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(GameRestController.class)
class GameRestControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private GameService gameService;

    @MockitoBean
    private GameUpdatePublisher publisher;

    @Test
    void createsSinglePlayerGame() throws Exception {
        Game game = new Game("ABC123", GameMode.SINGLE_PLAYER, Difficulty.HARD);
        Player human = new Player("Paulo", false);
        game.addPlayer(human);
        game.addPlayer(new Player("Computador", true));
        when(gameService.createGame("Paulo", GameMode.SINGLE_PLAYER, Difficulty.HARD))
                .thenReturn(new PlayerSession(game, human));

        mvc.perform(post("/api/games").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerName\":\"Paulo\",\"mode\":\"SINGLE_PLAYER\",\"difficulty\":\"HARD\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roomCode").value("ABC123"))
                .andExpect(jsonPath("$.playerId").value(human.getId()))
                .andExpect(jsonPath("$.game.opponentName").value("Computador"))
                .andExpect(jsonPath("$.game.ownBoard.length()").value(10))
                .andExpect(jsonPath("$.game.enemyBoard[0][0]").value("EMPTY"));
    }

    @Test
    void enemyShipsAreNotRevealed() throws Exception {
        Game game = new Game("ABC123", GameMode.SINGLE_PLAYER, Difficulty.EASY);
        Player human = new Player("Paulo", false);
        Player ai = new Player("Computador", true);
        game.addPlayer(human);
        game.addPlayer(ai);
        ai.getBoard().placeFleetRandomly(new java.util.Random(1));
        when(gameService.getGame(game.getId())).thenReturn(game);

        mvc.perform(get("/api/games/{id}", game.getId()).param("playerId", human.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enemyBoard[*][*]").value(org.hamcrest.Matchers.everyItem(
                        org.hamcrest.Matchers.is("EMPTY"))));
    }

    @Test
    void validatesCreateRequest() throws Exception {
        mvc.perform(post("/api/games").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerName\":\"\",\"mode\":\"MULTIPLAYER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.playerName").exists());
    }

    @Test
    void rejectsUnknownEnumValue() throws Exception {
        mvc.perform(post("/api/games").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerName\":\"Ana\",\"mode\":\"BATTLE_ROYALE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Requisição malformada."));
    }

    @Test
    void unknownRoomReturns404() throws Exception {
        when(gameService.joinGame("ZZZZZZ", "Ana")).thenThrow(new GameNotFoundException("Sala não encontrada: ZZZZZZ"));

        mvc.perform(post("/api/games/join").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomCode\":\"ZZZZZZ\",\"playerName\":\"Ana\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Sala não encontrada: ZZZZZZ"));
    }

    @Test
    void ruleViolationReturns400WithMessage() throws Exception {
        when(gameService.fire(eq("g1"), eq("p1"), any(Coordinate.class)))
                .thenThrow(new InvalidMoveException("Não é a sua vez."));

        mvc.perform(post("/api/games/g1/shots").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerId\":\"p1\",\"row\":1,\"col\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Não é a sua vez."));
        verify(gameService).fire("g1", "p1", Coordinate.of(1, 2));
    }

    @Test
    void shotOutsideBoardFailsValidation() throws Exception {
        mvc.perform(post("/api/games/g1/shots").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerId\":\"p1\",\"row\":10,\"col\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.row").exists());
    }

    @Test
    void fleetRequestValidatesEachShip() throws Exception {
        mvc.perform(post("/api/games/g1/fleet").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"playerId\":\"p1\",\"ships\":[{\"type\":\"CARRIER\",\"row\":0,\"col\":0}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors['ships[0].orientation']").exists());
    }

    @Test
    void randomFleetEndpointReturnsFiveShips() throws Exception {
        when(gameService.generateRandomFleet()).thenReturn(new GameService(
                new com.example.battleship.repository.GameRepository(),
                new com.example.battleship.service.AIService()).generateRandomFleet());

        mvc.perform(get("/api/games/random-fleet"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5));
    }

    @Test
    void missingPlayerIdIsBadRequest() throws Exception {
        mvc.perform(get("/api/games/{id}", "g1"))
                .andExpect(status().isBadRequest());
        verify(gameService, org.mockito.Mockito.never()).getGame(anyString());
    }
}
