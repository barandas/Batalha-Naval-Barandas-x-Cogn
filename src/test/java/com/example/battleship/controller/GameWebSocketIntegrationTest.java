package com.example.battleship.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.battleship.TestFleets;
import com.example.battleship.dto.JoinResponse;
import com.example.battleship.dto.ShipPlacementDto;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/** End-to-end multiplayer flow: REST to create/join/place, STOMP to shoot and receive updates. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GameWebSocketIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    private WebSocketStompClient stompClient;

    @AfterEach
    void tearDown() {
        if (stompClient != null) {
            stompClient.stop();
        }
    }

    @Test
    void shotSentOverStompUpdatesBothPlayers() throws Exception {
        JoinResponse host = rest.postForObject("/api/games",
                Map.of("playerName", "Host", "mode", "MULTIPLAYER"), JoinResponse.class);
        JoinResponse guest = rest.postForObject("/api/games/join",
                Map.of("roomCode", host.roomCode(), "playerName", "Guest"), JoinResponse.class);
        List<ShipPlacementDto> fleet = TestFleets.stacked().stream().map(ShipPlacementDto::from).toList();
        rest.postForObject("/api/games/" + host.gameId() + "/fleet",
                Map.of("playerId", host.playerId(), "ships", fleet), Map.class);
        rest.postForObject("/api/games/" + host.gameId() + "/fleet",
                Map.of("playerId", guest.playerId(), "ships", fleet), Map.class);

        StompSession hostSession = connect(host);
        StompSession guestSession = connect(guest);

        String base = "/topic/games/" + host.gameId() + "/players/";
        BlockingQueue<Map<String, Object>> hostUpdates = subscribe(hostSession, base + host.playerId());
        BlockingQueue<Map<String, Object>> guestUpdates = subscribe(guestSession, base + guest.playerId());
        BlockingQueue<Map<String, Object>> guestErrors = subscribe(guestSession, base + guest.playerId() + "/errors");
        Thread.sleep(300); // let subscriptions register

        // the payload claims to be the host, but the session is the guest's: the shot is the guest's and out of turn
        guestSession.send("/app/games/" + host.gameId() + "/shot",
                Map.of("playerId", host.playerId(), "row", 0, "col", 0));
        Map<String, Object> error = guestErrors.poll(5, TimeUnit.SECONDS);
        assertThat(error).isNotNull();
        assertThat(error.get("message")).isEqualTo("Não é a sua vez.");

        hostSession.send("/app/games/" + host.gameId() + "/shot", Map.of("row", 0, "col", 0));

        Map<String, Object> hostView = hostUpdates.poll(5, TimeUnit.SECONDS);
        Map<String, Object> guestView = guestUpdates.poll(5, TimeUnit.SECONDS);
        assertThat(hostView).isNotNull();
        assertThat(guestView).isNotNull();
        assertThat(hostView.get("yourTurn")).isEqualTo(false);
        assertThat(guestView.get("yourTurn")).isEqualTo(true);
        assertThat(((List<?>) ((List<?>) hostView.get("enemyBoard")).get(0)).get(0)).isEqualTo("HIT");
        assertThat(((List<?>) ((List<?>) guestView.get("ownBoard")).get(0)).get(0)).isEqualTo("HIT");
        assertThat(((List<?>) ((List<?>) hostView.get("enemyBoard")).get(0)).get(1)).isEqualTo("EMPTY");
    }

    @Test
    void aSessionCannotSubscribeToAnotherPlayersTopic() throws Exception {
        JoinResponse host = rest.postForObject("/api/games",
                Map.of("playerName", "Host", "mode", "MULTIPLAYER"), JoinResponse.class);
        JoinResponse guest = rest.postForObject("/api/games/join",
                Map.of("roomCode", host.roomCode(), "playerName", "Guest"), JoinResponse.class);
        StompSession guestSession = connect(guest);

        BlockingQueue<Map<String, Object>> stolen =
                subscribe(guestSession, "/topic/games/" + host.gameId() + "/players/" + host.playerId());
        Thread.sleep(300);
        rest.postForObject("/api/games/" + host.gameId() + "/fleet", Map.of("playerId", host.playerId(),
                "ships", TestFleets.stacked().stream().map(ShipPlacementDto::from).toList()), Map.class);

        assertThat(stolen.poll(1, TimeUnit.SECONDS)).isNull();
        assertThat(guestSession.isConnected()).isFalse();
    }

    @Test
    void connectingWithoutAValidPlayerIsRejected() {
        JoinResponse host = rest.postForObject("/api/games",
                Map.of("playerName", "Host", "mode", "MULTIPLAYER"), JoinResponse.class);

        assertThatThrownBy(() -> connect(new JoinResponse(host.gameId(), host.roomCode(), "intruder", null)))
                .isInstanceOfAny(ExecutionException.class, TimeoutException.class);
        assertThatThrownBy(() -> newClient().connectAsync("ws://localhost:" + port + "/ws",
                        new StompSessionHandlerAdapter() { }).get(5, TimeUnit.SECONDS))
                .isInstanceOfAny(ExecutionException.class, TimeoutException.class);
    }

    private StompSession connect(JoinResponse player) throws Exception {
        StompHeaders headers = new StompHeaders();
        headers.add("gameId", player.gameId());
        headers.add("playerId", player.playerId());
        return newClient().connectAsync("ws://localhost:" + port + "/ws", new WebSocketHttpHeaders(), headers,
                        new StompSessionHandlerAdapter() { })
                .get(5, TimeUnit.SECONDS);
    }

    private WebSocketStompClient newClient() {
        if (stompClient == null) {
            stompClient = new WebSocketStompClient(new StandardWebSocketClient());
            stompClient.setMessageConverter(new MappingJackson2MessageConverter());
        }
        return stompClient;
    }

    private static BlockingQueue<Map<String, Object>> subscribe(StompSession session, String destination) {
        BlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>();
        session.subscribe(destination, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
                queue.add((Map<String, Object>) payload);
            }
        });
        return queue;
    }
}
