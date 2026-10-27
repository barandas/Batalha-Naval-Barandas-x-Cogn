# Batalha Naval — Spring Boot

Batalha Naval web em **Java 17 + Spring Boot 3.5**, com modo **single player contra IA** (3 níveis) e
**multiplayer por código de sala** via WebSocket (STOMP). Frontend em HTML5, CSS3 e JavaScript puro, servido
pelo próprio Spring Boot.

> A versão original em Python (sockets TCP) foi preservada em [`legacy/`](legacy/).

## Requisitos

- JDK 17 ou superior (`JAVA_HOME` apontando para ele)
- Maven 3.6.3+

## Como executar

```bash
mvn spring-boot:run
```

Abra <http://localhost:8080>. Para outra porta: `PORT=9090 mvn spring-boot:run`.

Outros comandos:

```bash
mvn -q compile   # compila
mvn test         # roda os testes (JUnit 5 + Mockito)
mvn package      # gera target/battleship-1.0.0-SNAPSHOT.jar
java -jar target/battleship-1.0.0-SNAPSHOT.jar
```

### Jogando multiplayer

1. Jogador A informa o nome, escolhe **Multiplayer → Criar sala** e compartilha o código de 6 caracteres.
2. Jogador B (outra aba/navegador/máquina na mesma rede, usando o IP do host) escolhe **Entrar na sala** com o código.
3. Ambos posicionam a frota e confirmam; o criador da sala começa. Na revanche, quem começa alterna.

## Regras implementadas

- Tabuleiro 10x10 (linhas A–J, colunas 1–10).
- Frota: Porta-aviões (5), Encouraçado (4), Cruzador (3), Submarino (3), Destróier (2).
- Posicionamento manual (clique para posicionar, `R` ou botão para girar, clique num navio para movê-lo) ou
  aleatório. Navios não podem se sobrepor nem sair do tabuleiro (validado no cliente e no servidor).
- Turnos alternados (acertando ou errando). Mensagens de água / acerto / afundou.
- Vence quem afundar toda a frota adversária. O tabuleiro inimigo só mostra tiros e navios afundados — os
  navios restantes são revelados apenas no fim da partida.
- Tela de fim de jogo com revanche (no multiplayer, os dois precisam aceitar).

## Arquitetura

```
src/main/java/com/example/battleship
├── BattleshipApplication.java
├── model/        # domínio puro, sem Spring
│   ├── Board, Ship, ShipType, Coordinate, Orientation, ShipPlacement
│   ├── Game, Player, GameState, GameMode, Difficulty
│   └── ShotResult, ShotOutcome, LastShot, InvalidMoveException
├── service/
│   ├── GameService   # regras: salas, posicionamento, turnos, vitória, revanche
│   └── AIService     # escolha de tiros do computador
├── repository/
│   └── GameRepository  # armazenamento em memória (ConcurrentHashMap)
├── controller/
│   ├── GameRestController       # REST /api/games
│   ├── GameWebSocketController  # STOMP /app/games/{id}/...
│   └── GameUpdatePublisher      # envia a visão de cada jogador por STOMP
├── dto/          # requests validados (Bean Validation), GameView e mapper
└── config/
    ├── WebSocketConfig          # endpoint /ws, broker /topic, prefixo /app
    └── GlobalExceptionHandler   # @ControllerAdvice → ErrorResponse JSON
src/main/resources/static        # index.html, css/style.css, js/app.js, js/stomp-client.js
```

- **Modelo** (`model`): `Board` valida limites/sobreposição, registra tiros e detecta navio afundado e frota
  destruída. Não depende de Spring, então é testado diretamente.
- **GameService**: orquestra a partida com estados `WAITING_FOR_OPPONENT → PLACING_SHIPS → IN_PROGRESS → FINISHED`.
  Cada operação sincroniza no objeto `Game`, então jogadas simultâneas são seguras. No single player, a IA joga
  logo após o tiro do humano.
- **AIService** usa apenas informação pública (tiros, acertos e navios afundados), nunca a posição dos navios:
  - `EASY`: tiro aleatório em célula ainda não atingida.
  - `MEDIUM` (hunt/target): caça aleatória; após um acerto testa os vizinhos e, com dois acertos alinhados,
    segue a linha nas duas pontas até afundar.
  - `HARD`: mapa de densidade de probabilidade — conta todas as posições possíveis dos navios restantes
    (descartando águas e navios afundados), com peso alto para posições que cobrem acertos pendentes. Na fase de
    caça restringe a paridade *checkerboard* (`(linha + coluna) % menorNavioRestante == 0`).
- **Visão por jogador** (`GameViewMapper`): cada jogador recebe seu tabuleiro completo e o do oponente apenas
  com `HIT`/`MISS`/`SUNK`, evitando vazar a posição dos navios.

### API REST

| Método | Caminho | Descrição |
|---|---|---|
| `POST` | `/api/games` | Cria partida `{playerName, mode: SINGLE_PLAYER\|MULTIPLAYER, difficulty?: EASY\|MEDIUM\|HARD}` |
| `POST` | `/api/games/join` | Entra numa sala `{roomCode, playerName}` |
| `GET` | `/api/games/{gameId}?playerId=` | Estado da partida na visão do jogador |
| `GET` | `/api/games/random-fleet` | Sugestão de frota aleatória válida |
| `POST` | `/api/games/{gameId}/fleet` | Confirma a frota `{playerId, ships: [{type, row, col, orientation}]}` |
| `POST` | `/api/games/{gameId}/shots` | Atira `{playerId, row, col}` (alternativa REST ao STOMP) |
| `POST` | `/api/games/{gameId}/rematch` | Pede revanche `{playerId}` |

Erros retornam `{timestamp, status, error, message, fieldErrors}` (400 para regra/validação, 404 para sala ou
partida inexistente).

### WebSocket (STOMP)

- Endpoint: `ws://<host>:8080/ws` (WebSocket nativo; o frontend usa um cliente STOMP mínimo em `js/stomp-client.js`).
- Enviar: `/app/games/{gameId}/shot` `{playerId, row, col}` e `/app/games/{gameId}/rematch` `{playerId}`.
- Receber: `/topic/games/{gameId}/players/{playerId}` (visão atualizada) e `.../errors` (jogada inválida).

## Testes

`mvn test` executa 74 testes:

- `BoardTest`, `CoordinateTest`: posicionamento, limites, sobreposição, tiros (água/acerto/afundou), tiro repetido,
  frota destruída, posicionamento aleatório.
- `GameServiceTest` (Mockito para `AIService`): salas e códigos, validação de frota, turnos, vitória, resposta
  da IA, revanche.
- `AIServiceTest`: cada nível termina partidas sem repetir tiros, modo alvo/linha, paridade, densidade e
  comparação de desempenho entre níveis.
- `GameRestControllerTest` (`@WebMvcTest`): contratos REST e `GlobalExceptionHandler`.
- `GameWebSocketIntegrationTest`: fluxo multiplayer real com cliente STOMP.

## Limitações conhecidas

- Partidas ficam em memória: reiniciar o servidor encerra as salas.
- O `playerId` (UUID) funciona como token do jogador; não há autenticação.
