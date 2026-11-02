/**
 * Cliente STOMP 1.2 mínimo sobre WebSocket nativo (sem dependências externas).
 * Suporta CONNECT, SUBSCRIBE, SEND e recebimento de MESSAGE/ERROR.
 */
(function (global) {
    'use strict';

    const NULL = '\u0000';

    class StompClient {
        constructor(url) {
            this.url = url;
            this.ws = null;
            this.connected = false;
            this.subscriptions = new Map();
            this.nextId = 0;
        }

        connect(onConnect, onClose) {
            this.ws = new WebSocket(this.url);
            this.ws.onopen = () => {
                this._sendFrame('CONNECT', {'accept-version': '1.2', host: location.host, 'heart-beat': '0,0'});
            };
            this.ws.onmessage = (event) => this._handleData(event.data, onConnect);
            this.ws.onclose = () => {
                const wasConnected = this.connected;
                this.connected = false;
                if (onClose) onClose(wasConnected);
            };
            this.ws.onerror = () => { /* tratado em onclose */ };
        }

        subscribe(destination, callback) {
            const id = 'sub-' + (this.nextId++);
            this.subscriptions.set(id, callback);
            this._sendFrame('SUBSCRIBE', {id, destination});
            return id;
        }

        send(destination, payload) {
            this._sendFrame('SEND', {destination, 'content-type': 'application/json'}, JSON.stringify(payload));
        }

        disconnect() {
            if (this.ws) {
                this.ws.onclose = null;
                if (this.connected) this._sendFrame('DISCONNECT', {});
                this.ws.close();
            }
            this.connected = false;
            this.subscriptions.clear();
        }

        _sendFrame(command, headers, body = '') {
            if (!this.ws || this.ws.readyState !== WebSocket.OPEN) return;
            let frame = command + '\n';
            for (const [key, value] of Object.entries(headers)) {
                frame += key + ':' + value + '\n';
            }
            this.ws.send(frame + '\n' + body + NULL);
        }

        _handleData(data, onConnect) {
            for (const raw of String(data).split(NULL)) {
                const frame = raw.replace(/^[\r\n]+/, '');
                if (!frame) continue;
                const separator = frame.indexOf('\n\n');
                const head = separator >= 0 ? frame.substring(0, separator) : frame;
                const body = separator >= 0 ? frame.substring(separator + 2) : '';
                const [command, ...headerLines] = head.split('\n');
                const headers = {};
                for (const line of headerLines) {
                    const idx = line.indexOf(':');
                    if (idx > 0) headers[line.substring(0, idx)] = line.substring(idx + 1);
                }
                this._dispatch(command.trim(), headers, body, onConnect);
            }
        }

        _dispatch(command, headers, body, onConnect) {
            if (command === 'CONNECTED') {
                this.connected = true;
                if (onConnect) onConnect();
            } else if (command === 'MESSAGE') {
                const callback = this.subscriptions.get(headers.subscription);
                if (!callback) return;
                let payload = body;
                try { payload = JSON.parse(body); } catch (e) { /* corpo não-JSON */ }
                callback(payload, headers);
            } else if (command === 'ERROR') {
                console.error('STOMP ERROR', headers.message, body);
            }
        }
    }

    global.StompClient = StompClient;
})(window);
