package com.example.battleship.controller;

import com.example.battleship.dto.CreateGameRequest;
import com.example.battleship.dto.GameView;
import com.example.battleship.dto.GameViewMapper;
import com.example.battleship.dto.JoinGameRequest;
import com.example.battleship.dto.JoinResponse;
import com.example.battleship.dto.PlaceFleetRequest;
import com.example.battleship.dto.PlayerRequest;
import com.example.battleship.dto.ShipPlacementDto;
import com.example.battleship.dto.ShotRequest;
import com.example.battleship.model.Coordinate;
import com.example.battleship.model.Game;
import com.example.battleship.service.GameService;
import com.example.battleship.service.PlayerSession;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/games")
public class GameRestController {

    private final GameService gameService;
    private final GameUpdatePublisher publisher;

    public GameRestController(GameService gameService, GameUpdatePublisher publisher) {
        this.gameService = gameService;
        this.publisher = publisher;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public JoinResponse createGame(@Valid @RequestBody CreateGameRequest request) {
        PlayerSession session = gameService.createGame(request.playerName(), request.mode(), request.difficulty());
        return toResponse(session);
    }

    @PostMapping("/join")
    public JoinResponse joinGame(@Valid @RequestBody JoinGameRequest request) {
        PlayerSession session = gameService.joinGame(request.roomCode(), request.playerName());
        publisher.publish(session.game());
        return toResponse(session);
    }

    @GetMapping("/{gameId}")
    public GameView getGame(@PathVariable String gameId, @RequestParam String playerId) {
        return GameViewMapper.toView(gameService.getGame(gameId), playerId);
    }

    @GetMapping("/random-fleet")
    public List<ShipPlacementDto> randomFleet() {
        return gameService.generateRandomFleet().stream().map(ShipPlacementDto::from).toList();
    }

    @PostMapping("/{gameId}/fleet")
    public GameView submitFleet(@PathVariable String gameId, @Valid @RequestBody PlaceFleetRequest request) {
        Game game = gameService.submitFleet(gameId, request.playerId(),
                request.ships().stream().map(ShipPlacementDto::toModel).toList());
        publisher.publish(game);
        return GameViewMapper.toView(game, request.playerId());
    }

    @PostMapping("/{gameId}/shots")
    public GameView fire(@PathVariable String gameId, @Valid @RequestBody ShotRequest request) {
        gameService.fire(gameId, request.playerId(), Coordinate.of(request.row(), request.col()));
        Game game = gameService.getGame(gameId);
        publisher.publish(game);
        return GameViewMapper.toView(game, request.playerId());
    }

    @PostMapping("/{gameId}/rematch")
    public GameView rematch(@PathVariable String gameId, @Valid @RequestBody PlayerRequest request) {
        Game game = gameService.requestRematch(gameId, request.playerId());
        publisher.publish(game);
        return GameViewMapper.toView(game, request.playerId());
    }

    private static JoinResponse toResponse(PlayerSession session) {
        Game game = session.game();
        String playerId = session.player().getId();
        return new JoinResponse(game.getId(), game.getRoomCode(), playerId, GameViewMapper.toView(game, playerId));
    }
}
