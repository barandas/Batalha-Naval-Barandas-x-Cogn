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
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

/** Presence derived from STOMP sessions: a drop warns the opponent, only a persistent one ends the battle. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "battleship.reconnect-grace=2s")
class PresenceIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    private WebSocketStompClient stompClient;
    private JoinResponse host;
    private JoinResponse guest;

    @BeforeEach
    void startBattle() {
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        stompClient.setMessageConverter(new MappingJackson2MessageConverter());
        host = rest.postForObject("/api/games", Map.of("playerName", "Host", "mode", "MULTIPLAYER"),
                JoinResponse.class);
        guest = rest.postForObject("/api/games/join", Map.of("roomCode", host.roomCode(), "playerName", "Guest"),
                JoinResponse.class);
        List<ShipPlacementDto> fleet = TestFleets.stacked().stream().map(ShipPlacementDto::from).toList();
        rest.postForObject("/api/games/" + host.gameId() + "/fleet",
                Map.of("playerId", host.playerId(), "ships", fleet), Map.class);
        rest.postForObject("/api/games/" + host.gameId() + "/fleet",
                Map.of("playerId", guest.playerId(), "ships", fleet), Map.class);
    }

    @AfterEach
    void tearDown() {
        stompClient.stop();
    }

    @Test
    void persistentDisconnectionEndsTheBattleByForfeit() throws Exception {
        BlockingQueue<Map<String, Object>> hostUpdates = subscribe(connect(), host.playerId());
        StompSession guestSession = connect();
        subscribe(guestSession, guest.playerId());
        Thread.sleep(300);

        guestSession.disconnect();

        Map<String, Object> warning = await(hostUpdates, v -> Boolean.FALSE.equals(v.get("opponentConnected")));
        assertThat(warning.get("state")).isEqualTo("IN_PROGRESS");
        assertThat(warning.get("opponentReconnectDeadline")).isNotNull();

        Map<String, Object> end = await(hostUpdates, v -> "FINISHED".equals(v.get("state")));
        assertThat(end.get("youWon")).isEqualTo(true);
        assertThat(end.get("endReason")).isEqualTo("OPPONENT_DISCONNECTED");
        assertThat(end.get("opponentLeft")).isEqualTo(true);
    }

    @Test
    void reconnectingWithinTheGracePeriodKeepsTheBattle() throws Exception {
        BlockingQueue<Map<String, Object>> hostUpdates = subscribe(connect(), host.playerId());
        StompSession guestSession = connect();
        subscribe(guestSession, guest.playerId());
        Thread.sleep(300);

        guestSession.disconnect();
        await(hostUpdates, v -> Boolean.FALSE.equals(v.get("opponentConnected")));
        subscribe(connect(), guest.playerId());

        Map<String, Object> back = await(hostUpdates, v -> Boolean.TRUE.equals(v.get("opponentConnected")));
        assertThat(back.get("state")).isEqualTo("IN_PROGRESS");
        Thread.sleep(2500);
        Map<?, ?> view = rest.getForObject("/api/games/" + host.gameId() + "?playerId=" + host.playerId(), Map.class);
        assertThat(view.get("state")).isEqualTo("IN_PROGRESS");
        assertThat(view.get("opponentConnected")).isEqualTo(true);
    }

    @Test
    void voluntaryExitIsImmediateAndNotReportedAsADisconnection() throws Exception {
        BlockingQueue<Map<String, Object>> hostUpdates = subscribe(connect(), host.playerId());
        StompSession guestSession = connect();
        subscribe(guestSession, guest.playerId());
        Thread.sleep(300);

        rest.postForObject("/api/games/" + host.gameId() + "/leave", Map.of("playerId", guest.playerId()), Map.class);
        guestSession.disconnect();

        Map<String, Object> end = await(hostUpdates, v -> "FINISHED".equals(v.get("state")));
        assertThat(end.get("endReason")).isEqualTo("OPPONENT_LEFT");
        assertThat(hostUpdates.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    private StompSession connect() throws Exception {
        return stompClient.connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() { })
                .get(5, TimeUnit.SECONDS);
    }

    private static Map<String, Object> await(BlockingQueue<Map<String, Object>> queue,
                                             Predicate<Map<String, Object>> condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 6000;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> view = queue.poll(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
            if (view != null && condition.test(view)) {
                return view;
            }
        }
        throw new AssertionError("Expected update not received");
    }

    private BlockingQueue<Map<String, Object>> subscribe(StompSession session, String playerId) {
        BlockingQueue<Map<String, Object>> queue = new LinkedBlockingQueue<>();
        session.subscribe("/topic/games/" + host.gameId() + "/players/" + playerId, new StompFrameHandler() {
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
