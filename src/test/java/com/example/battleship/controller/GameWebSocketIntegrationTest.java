package com.example.battleship.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.battleship.TestFleets;
import com.example.battleship.dto.JoinResponse;
import com.example.battleship.dto.ShipPlacementDto;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
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

        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
        StompSession session = stompClient
                .connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() { })
                .get(5, TimeUnit.SECONDS);

        String base = "/topic/games/" + host.gameId() + "/players/";
        BlockingQueue<Map<String, Object>> hostUpdates = subscribe(session, base + host.playerId());
        BlockingQueue<Map<String, Object>> guestUpdates = subscribe(session, base + guest.playerId());
        BlockingQueue<Map<String, Object>> guestErrors = subscribe(session, base + guest.playerId() + "/errors");
        Thread.sleep(300); // let subscriptions register

        session.send("/app/games/" + host.gameId() + "/shot", Map.of("playerId", guest.playerId(), "row", 0, "col", 0));
        Map<String, Object> error = guestErrors.poll(5, TimeUnit.SECONDS);
        assertThat(error).isNotNull();
        assertThat(error.get("message")).isEqualTo("Não é a sua vez.");

        session.send("/app/games/" + host.gameId() + "/shot", Map.of("playerId", host.playerId(), "row", 0, "col", 0));

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
