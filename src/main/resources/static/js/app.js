(function () {
    'use strict';

    const SIZE = 10;
    const ROWS = 'ABCDEFGHIJ';
    const SHIPS = [
        {type: 'CARRIER', name: 'Porta-aviões', size: 5},
        {type: 'BATTLESHIP', name: 'Encouraçado', size: 4},
        {type: 'CRUISER', name: 'Cruzador', size: 3},
        {type: 'SUBMARINE', name: 'Submarino', size: 3},
        {type: 'DESTROYER', name: 'Destróier', size: 2}
    ];
    const SHIP_BY_TYPE = Object.fromEntries(SHIPS.map(s => [s.type, s]));
    const STORAGE_KEY = 'battleship.session';

    const state = {
        mode: 'SINGLE_PLAYER',
        session: null,          // {gameId, playerId}
        view: null,
        round: null,
        stomp: null,
        placement: {ships: {}, selected: 'CARRIER', orientation: 'HORIZONTAL', hover: null},
        modalDismissed: false
    };

    const $ = (id) => document.getElementById(id);

    // ---------------------------------------------------------------- API
    async function api(path, options = {}) {
        const response = await fetch('/api/games' + path, {
            headers: {'Content-Type': 'application/json'},
            ...options,
            body: options.body ? JSON.stringify(options.body) : undefined
        });
        const data = await response.json().catch(() => null);
        if (!response.ok) {
            const error = new Error((data && data.message) || 'Erro ' + response.status);
            error.status = response.status;
            throw error;
        }
        return data;
    }

    // ---------------------------------------------------------------- Tela inicial
    function initHome() {
        document.querySelectorAll('.mode-tabs .tab').forEach(tab => {
            tab.addEventListener('click', () => {
                state.mode = tab.dataset.mode;
                document.querySelectorAll('.mode-tabs .tab').forEach(t => t.classList.toggle('active', t === tab));
                $('panel-single').classList.toggle('active', state.mode === 'SINGLE_PLAYER');
                $('panel-multi').classList.toggle('active', state.mode === 'MULTIPLAYER');
            });
        });
        $('player-name').value = localStorage.getItem('battleship.name') || '';
        $('btn-start-single').addEventListener('click', () => createGame('SINGLE_PLAYER'));
        $('btn-create-room').addEventListener('click', () => createGame('MULTIPLAYER'));
        $('btn-join-room').addEventListener('click', joinGame);
        $('room-code').addEventListener('input', e => { e.target.value = e.target.value.toUpperCase(); });
        $('room-code').addEventListener('keydown', e => { if (e.key === 'Enter') joinGame(); });
    }

    function playerName() {
        const name = $('player-name').value.trim();
        if (!name) {
            throw new Error('Informe seu nome para começar.');
        }
        localStorage.setItem('battleship.name', name);
        return name;
    }

    async function createGame(mode) {
        $('home-error').textContent = '';
        try {
            const body = {playerName: playerName(), mode};
            if (mode === 'SINGLE_PLAYER') body.difficulty = $('difficulty').value;
            enterGame(await api('', {method: 'POST', body}));
        } catch (e) {
            $('home-error').textContent = e.message;
        }
    }

    async function joinGame() {
        $('home-error').textContent = '';
        try {
            const roomCode = $('room-code').value.trim();
            if (!roomCode) throw new Error('Informe o código da sala.');
            enterGame(await api('/join', {method: 'POST', body: {roomCode, playerName: playerName()}}));
        } catch (e) {
            $('home-error').textContent = e.message;
        }
    }

    function enterGame(joinResponse) {
        state.session = {gameId: joinResponse.gameId, playerId: joinResponse.playerId};
        sessionStorage.setItem(STORAGE_KEY, JSON.stringify(state.session));
        state.round = null;
        showScreen('screen-game');
        connectSocket();
        render(joinResponse.game);
    }

    function leaveGame() {
        if (state.stomp) state.stomp.disconnect();
        state.stomp = null;
        state.session = null;
        state.view = null;
        sessionStorage.removeItem(STORAGE_KEY);
        $('connection-indicator').hidden = true;
        hideModal();
        showScreen('screen-home');
    }

    function showScreen(id) {
        document.querySelectorAll('.screen').forEach(s => s.classList.toggle('active', s.id === id));
    }

    // ---------------------------------------------------------------- WebSocket
    function connectSocket() {
        if (state.stomp) state.stomp.disconnect();
        const session = state.session;
        const protocol = location.protocol === 'https:' ? 'wss://' : 'ws://';
        const client = new StompClient(protocol + location.host + '/ws');
        state.stomp = client;
        client.connect(() => {
            setConnection(true);
            const base = '/topic/games/' + session.gameId + '/players/' + session.playerId;
            client.subscribe(base, view => render(view));
            client.subscribe(base + '/errors', error => flash(error.message, true));
            refresh(); // sincroniza caso algo tenha mudado antes da inscrição
        }, () => {
            setConnection(false);
            if (state.stomp === client && state.session === session) {
                setTimeout(() => { if (state.stomp === client) connectSocket(); }, 2000);
            }
        });
    }

    function setConnection(online) {
        const el = $('connection-indicator');
        el.hidden = false;
        el.textContent = online ? 'online' : 'offline';
        el.classList.toggle('online', online);
        el.classList.toggle('offline', !online);
    }

    async function refresh() {
        if (!state.session) return;
        try {
            render(await api('/' + state.session.gameId + '?playerId=' + encodeURIComponent(state.session.playerId)));
        } catch (e) {
            if (e.status === 404 || e.status === 400) leaveGame();
        }
    }

    // ---------------------------------------------------------------- Posicionamento
    function resetPlacement() {
        state.placement = {ships: {}, selected: 'CARRIER', orientation: 'HORIZONTAL', hover: null};
        $('placement-error').textContent = '';
    }

    function cellsFor(type, row, col, orientation) {
        const size = SHIP_BY_TYPE[type].size;
        return Array.from({length: size}, (_, i) =>
            orientation === 'HORIZONTAL' ? [row, col + i] : [row + i, col]);
    }

    function occupiedBy(row, col, ignoreType) {
        for (const [type, p] of Object.entries(state.placement.ships)) {
            if (type === ignoreType) continue;
            if (cellsFor(type, p.row, p.col, p.orientation).some(([r, c]) => r === row && c === col)) return type;
        }
        return null;
    }

    function isValidPlacement(type, row, col, orientation) {
        return cellsFor(type, row, col, orientation).every(([r, c]) =>
            r >= 0 && r < SIZE && c >= 0 && c < SIZE && !occupiedBy(r, c, type));
    }

    function placeAt(row, col) {
        const p = state.placement;
        const existing = occupiedBy(row, col, null);
        if (existing) {
            p.selected = existing;
            p.orientation = p.ships[existing].orientation;
            delete p.ships[existing];
            $('placement-error').textContent = '';
            renderPlacement();
            return;
        }
        if (!p.selected) return;
        if (!isValidPlacement(p.selected, row, col, p.orientation)) {
            $('placement-error').textContent = 'Posição inválida: o navio sairia do tabuleiro ou sobreporia outro.';
            return;
        }
        $('placement-error').textContent = '';
        p.ships[p.selected] = {row, col, orientation: p.orientation};
        p.selected = SHIPS.map(s => s.type).find(t => !p.ships[t]) || null;
        renderPlacement();
    }

    function rotate() {
        const p = state.placement;
        p.orientation = p.orientation === 'HORIZONTAL' ? 'VERTICAL' : 'HORIZONTAL';
        renderPlacement();
    }

    async function randomPlacement() {
        try {
            const fleet = await api('/random-fleet');
            resetPlacement();
            fleet.forEach(s => { state.placement.ships[s.type] = {row: s.row, col: s.col, orientation: s.orientation}; });
            state.placement.selected = null;
            renderPlacement();
        } catch (e) {
            $('placement-error').textContent = e.message;
        }
    }

    async function confirmFleet() {
        const ships = Object.entries(state.placement.ships).map(([type, p]) => ({type, ...p}));
        try {
            render(await api('/' + state.session.gameId + '/fleet', {
                method: 'POST', body: {playerId: state.session.playerId, ships}
            }));
        } catch (e) {
            $('placement-error').textContent = e.message;
        }
    }

    function initPlacement() {
        $('btn-rotate').addEventListener('click', rotate);
        $('btn-random').addEventListener('click', randomPlacement);
        $('btn-clear').addEventListener('click', () => { resetPlacement(); renderPlacement(); });
        $('btn-ready').addEventListener('click', confirmFleet);
        document.addEventListener('keydown', e => {
            if ((e.key === 'r' || e.key === 'R') && isPlacing() && document.activeElement.tagName !== 'INPUT') rotate();
        });
    }

    function isPlacing() {
        const v = state.view;
        return v && !v.youReady && (v.state === 'WAITING_FOR_OPPONENT' || v.state === 'PLACING_SHIPS');
    }

    // ---------------------------------------------------------------- Renderização
    function buildBoard(container, cellClass, onClick, onHover) {
        container.innerHTML = '';
        container.appendChild(label(''));
        for (let c = 0; c < SIZE; c++) container.appendChild(label(String(c + 1)));
        for (let r = 0; r < SIZE; r++) {
            container.appendChild(label(ROWS[r]));
            for (let c = 0; c < SIZE; c++) {
                const cell = document.createElement('button');
                cell.type = 'button';
                cell.className = 'cell ' + cellClass(r, c);
                cell.setAttribute('aria-label', ROWS[r] + (c + 1));
                cell.dataset.row = r;
                cell.dataset.col = c;
                if (onClick) cell.addEventListener('click', () => onClick(r, c));
                if (onHover) {
                    cell.addEventListener('mouseenter', () => onHover(r, c));
                    cell.addEventListener('mouseleave', () => onHover(null, null));
                }
                container.appendChild(cell);
            }
        }
    }

    function label(text) {
        const el = document.createElement('div');
        el.className = 'label';
        el.textContent = text;
        return el;
    }

    function stateClass(cellState) {
        return (cellState || 'EMPTY').toLowerCase();
    }

    function placementCellClass(r, c) {
        const p = state.placement;
        const classes = [];
        if (occupiedBy(r, c, null)) classes.push('ship');
        if (p.hover && p.selected) {
            const preview = cellsFor(p.selected, p.hover.row, p.hover.col, p.orientation);
            if (preview.some(([pr, pc]) => pr === r && pc === c)) {
                classes.push(isValidPlacement(p.selected, p.hover.row, p.hover.col, p.orientation)
                    ? 'preview' : 'preview-invalid');
            }
        }
        return classes.join(' ') || 'empty';
    }

    function paintPlacementBoard() {
        $('own-board').querySelectorAll('.cell').forEach(cell => {
            cell.className = 'cell ' + placementCellClass(Number(cell.dataset.row), Number(cell.dataset.col));
        });
    }

    function renderPlacement() {
        const p = state.placement;
        buildBoard($('own-board'), placementCellClass, placeAt, (r, c) => {
            p.hover = r === null ? null : {row: r, col: c};
            paintPlacementBoard();
        });
        $('own-board').classList.add('placing');

        const picker = $('ship-picker');
        picker.innerHTML = '';
        SHIPS.forEach(ship => {
            const li = document.createElement('li');
            const placed = Boolean(p.ships[ship.type]);
            li.className = 'ship-option' + (p.selected === ship.type ? ' selected' : '') + (placed ? ' placed' : '');
            li.innerHTML = '<span class="ship-name">' + ship.name + '</span>' +
                '<span class="ship-blocks">' + '■'.repeat(ship.size) + '</span>';
            li.addEventListener('click', () => {
                if (placed) delete p.ships[ship.type];
                p.selected = ship.type;
                renderPlacement();
            });
            picker.appendChild(li);
        });
        $('orientation-label').textContent = p.orientation === 'HORIZONTAL' ? 'horizontal' : 'vertical';
        paintPlacementBoard();
        $('btn-ready').disabled = Object.keys(p.ships).length !== SHIPS.length;
    }

    function render(view) {
        if (!view || !state.session || view.gameId !== state.session.gameId) return;
        const previous = state.view;
        state.view = view;
        if (state.round !== view.round) {
            state.round = view.round;
            resetPlacement();
            state.modalDismissed = false;
        }

        $('label-me').textContent = view.playerName;
        $('label-opponent').textContent = view.opponentName || 'aguardando...';
        $('room-info').classList.toggle('hidden', view.mode !== 'MULTIPLAYER');
        $('room-code-display').textContent = view.roomCode;

        const placing = isPlacing();
        $('placement-panel').classList.toggle('hidden', !placing);
        $('enemy-panel').classList.toggle('hidden', placing);
        $('own-board').classList.remove('placing');

        if (placing) {
            renderPlacement();
        } else {
            buildBoard($('own-board'), (r, c) => stateClass(view.ownBoard[r][c]) + lastShotClass(view, r, c, false));
            const canShoot = view.state === 'IN_PROGRESS' && view.yourTurn;
            buildBoard($('enemy-board'),
                (r, c) => stateClass(view.enemyBoard[r][c]) + lastShotClass(view, r, c, true)
                    + (canShoot && view.enemyBoard[r][c] === 'EMPTY' ? ' target' : ''),
                canShoot ? fire : null);
            $('enemy-board').classList.toggle('active', canShoot);
        }
        renderFleet($('own-fleet'), view.yourFleet);
        renderFleet($('enemy-fleet'), view.enemyFleet);
        renderStatus(view, previous);
        renderLog(view.events);
        renderGameOver(view);
    }

    function lastShotClass(view, r, c, onEnemyBoard) {
        const s = view.lastShot;
        return s && s.byYou === onEnemyBoard && s.row === r && s.col === c ? ' last' : '';
    }

    function renderFleet(list, fleet) {
        list.innerHTML = '';
        (fleet || []).forEach(ship => {
            const li = document.createElement('li');
            li.className = ship.sunk ? 'sunk' : '';
            li.textContent = ship.name + ' (' + ship.size + ')';
            list.appendChild(li);
        });
    }

    function renderStatus(view, previous) {
        const turn = $('turn-indicator');
        let turnText = '';
        let turnClass = '';
        switch (view.state) {
            case 'WAITING_FOR_OPPONENT':
                turnText = view.youReady ? 'Aguardando oponente entrar na sala...'
                    : 'Compartilhe o código da sala e posicione sua frota.';
                break;
            case 'PLACING_SHIPS':
                turnText = view.youReady ? 'Aguardando ' + (view.opponentName || 'oponente') + ' posicionar a frota...'
                    : 'Posicione seus navios.';
                break;
            case 'IN_PROGRESS':
                turnText = view.yourTurn ? 'Sua vez! Escolha um alvo.' : 'Vez de ' + view.opponentName + '...';
                turnClass = view.yourTurn ? 'your-turn' : 'their-turn';
                break;
            case 'FINISHED':
                turnText = view.youWon ? 'Vitória!' : 'Derrota.';
                turnClass = view.youWon ? 'your-turn' : 'their-turn';
                break;
        }
        turn.textContent = turnText;
        turn.className = 'turn-indicator ' + turnClass;

        const shot = view.lastShot;
        const prevShot = previous && previous.lastShot;
        if (shot && JSON.stringify(shot) !== JSON.stringify(prevShot)) {
            flash(describeShot(shot, view), false, shot.result);
        } else if (!shot) {
            $('status-message').textContent = '';
            $('status-message').className = 'status-message';
        }
    }

    function describeShot(shot, view) {
        const where = ROWS[shot.row] + (shot.col + 1);
        const ship = shot.sunkShip ? SHIP_BY_TYPE[shot.sunkShip].name : '';
        if (shot.byYou) {
            if (shot.result === 'MISS') return where + ': Água!';
            if (shot.result === 'HIT') return where + ': Acertou!';
            return where + ': Você afundou o ' + ship + '!';
        }
        const who = view.opponentName || 'Oponente';
        if (shot.result === 'MISS') return who + ' atirou em ' + where + ' e errou.';
        if (shot.result === 'HIT') return who + ' acertou seu navio em ' + where + '!';
        return who + ' afundou seu ' + ship + '!';
    }

    function flash(message, isError, result) {
        const el = $('status-message');
        el.textContent = message;
        el.className = 'status-message ' + (isError ? 'error' : (result || '').toLowerCase());
    }

    function renderLog(events) {
        const log = $('event-log');
        log.innerHTML = '';
        (events || []).forEach(e => {
            const li = document.createElement('li');
            li.textContent = e;
            log.appendChild(li);
        });
    }

    function renderGameOver(view) {
        if (view.state !== 'FINISHED') {
            hideModal();
            return;
        }
        $('gameover-title').textContent = view.youWon ? '🏆 Vitória!' : '💥 Derrota';
        $('gameover-text').textContent = view.youWon
            ? 'Você afundou toda a frota de ' + view.opponentName + '!'
            : view.opponentName + ' afundou toda a sua frota.';
        let rematch = '';
        if (view.rematchRequestedByYou) rematch = 'Aguardando ' + view.opponentName + ' aceitar a revanche...';
        else if (view.rematchRequestedByOpponent) rematch = view.opponentName + ' quer revanche!';
        $('rematch-status').textContent = rematch;
        $('btn-rematch').disabled = view.rematchRequestedByYou;
        if (!state.modalDismissed) $('gameover-modal').classList.remove('hidden');
    }

    function hideModal() {
        $('gameover-modal').classList.add('hidden');
    }

    // ---------------------------------------------------------------- Ações de jogo
    async function fire(row, col) {
        const v = state.view;
        if (!v || !v.yourTurn || v.enemyBoard[row][col] !== 'EMPTY') return;
        const payload = {playerId: state.session.playerId, row, col};
        if (state.stomp && state.stomp.connected) {
            state.stomp.send('/app/games/' + state.session.gameId + '/shot', payload);
            return;
        }
        try {
            render(await api('/' + state.session.gameId + '/shots', {method: 'POST', body: payload}));
        } catch (e) {
            flash(e.message, true);
        }
    }

    async function requestRematch() {
        const payload = {playerId: state.session.playerId};
        try {
            render(await api('/' + state.session.gameId + '/rematch', {method: 'POST', body: payload}));
        } catch (e) {
            $('rematch-status').textContent = e.message;
        }
    }

    function initGameControls() {
        $('btn-rematch').addEventListener('click', requestRematch);
        $('btn-view-boards').addEventListener('click', () => { state.modalDismissed = true; hideModal(); });
        $('btn-menu').addEventListener('click', leaveGame);
        $('btn-leave').addEventListener('click', leaveGame);
        $('btn-copy-code').addEventListener('click', () => {
            if (navigator.clipboard && state.view) navigator.clipboard.writeText(state.view.roomCode);
        });
    }

    function restoreSession() {
        try {
            const saved = JSON.parse(sessionStorage.getItem(STORAGE_KEY));
            if (saved && saved.gameId && saved.playerId) {
                state.session = saved;
                showScreen('screen-game');
                connectSocket();
                refresh();
            }
        } catch (e) {
            sessionStorage.removeItem(STORAGE_KEY);
        }
    }

    initHome();
    initPlacement();
    initGameControls();
    restoreSession();
})();
