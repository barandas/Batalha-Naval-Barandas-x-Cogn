import socket
import threading
from game import Game

def main():
    """Função principal para configurar e iniciar o servidor do jogo."""
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.bind(('0.0.0.0', 8080))
    server.listen(2)
    print("Servidor iniciado na porta 8080")

    game = Game()

    # Aguarda a conexão de dois jogadores para iniciar o jogo
    while len(game.players) < 2:
        conn, addr = server.accept()
        player_id = game.add_player(conn, addr)
        threading.Thread(target=game.handle_client, args=(player_id,)).start()

    print("Dois jogadores conectados, iniciando o jogo!")

if __name__ == "__main__":
    main()
