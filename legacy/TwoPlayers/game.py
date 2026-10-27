import threading
import random
import time

SIZE = 15        
NUM_NAVIOS = 10   
MAX_TENT = 10   

def inicializa_campo():
    """Inicializa um campo de jogo vazio."""
    return [[' ' for _ in range(SIZE)] for _ in range(SIZE)]

def coloca_navios(campo):
    """Coloca os navios aleatoriamente no campo de jogo."""
    navios_colocados = 0
    while navios_colocados < NUM_NAVIOS:
        x = random.randint(0, SIZE - 1)
        y = random.randint(0, SIZE - 1)
        if campo[x][y] == ' ':
            campo[x][y] = 'S'
            navios_colocados += 1

def exibe_campo(campo):
    """Formata e exibe visualmente o campo de jogo."""
    header = "    " + " ".join([chr(c + ord('A')) for c in range(SIZE)]) + "\n"
    display = header
    for i in range(SIZE):
        row_number = f"{i + 1:2}"
        row_content = row_number.rjust(2) + "  " + " ".join(campo[i])
        display += row_content + "\n"
    return display

class Game:
    """Classe para gerenciar o estado do jogo Batalha Naval."""
    def __init__(self):
        """Inicializa o estado do jogo."""
        self.lock = threading.Lock()
        self.players = []             # Lista de jogadores conectados
        self.turn = 0                 # Índice do jogador cuja vez é atual
        self.game_over = [False, False]  # Indica se cada jogador terminou o jogo
        self.result_displayed = False     # Flag para verificar se os resultados já foram exibidos

    def add_player(self, conn, addr):
        """Adiciona um novo jogador ao jogo."""
        player_id = len(self.players)
        player_info = {
            'conn': conn,                     # Socket de conexão do jogador
            'addr': addr,                     # Endereço do jogador
            'campo': inicializa_campo(),      # Campo de jogo do jogador
            'campo_exibicao': inicializa_campo(),  # Campo de exibição para o jogador
            'tentativas': 0,                  # Número de tentativas do jogador
            'acertos': 0,                     # Número de navios derrubados pelo jogador
            'coordenadas_usadas': set(),      # Conjunto de coordenadas usadas pelo jogador
            'wait_message_sent': False        # Flag para verificar se a mensagem de espera foi enviada
        }
        coloca_navios(player_info['campo'])
        self.players.append(player_info)
        return player_id

    def other_player_id(self, player_id):
        """Retorna o ID do outro jogador."""
        return 1 - player_id

    def handle_client(self, player_id):
        """Lida com a lógica do jogo para um jogador específico."""
        player = self.players[player_id]
        conn = player['conn']
        addr = player['addr']

        print(f"Conexão estabelecida com {addr}")
        conn.sendall("\nBem-vindo ao jogo Batalha Naval!\n".encode('utf-8'))

        while player['tentativas'] < MAX_TENT and player['acertos'] < NUM_NAVIOS:
            with self.lock:
                # Verifica se há menos de 2 jogadores
                if len(self.players) < 2:
                    try:
                        conn.sendall("Aguardando o segundo jogador se conectar...\n".encode('utf-8'))
                    except ConnectionResetError:
                        print(f"Conexão com {addr} foi resetada.")
                        return
                    time.sleep(1)
                    continue

                # Verifica se não é a vez deste jogador
                if self.turn != player_id:
                    if not player['wait_message_sent']:
                        try:
                            conn.sendall("Aguarde sua vez...\n".encode('utf-8'))
                        except ConnectionResetError:
                            print(f"Conexão com {addr} foi resetada.")
                            return
                        player['wait_message_sent'] = True
                    continue
                else:
                    player['wait_message_sent'] = False

                # Loop para esperar a jogada válida do jogador
                while True:
                    try:
                        conn.sendall("Sua vez! Informe a jogada (ex: A 1): ".encode('utf-8'))
                        data = conn.recv(1024).decode('utf-8')
                        if not data or data.lower() == 'sair':
                            conn.close()
                            return
                        try:
                            letra, numero = data.split()
                            x = int(numero) - 1
                            y = ord(letra.upper()) - ord('A')
                            if 0 <= x < SIZE and 0 <= y < SIZE:
                                if (x, y) not in player['coordenadas_usadas']:
                                    player['coordenadas_usadas'].add((x, y))
                                    break
                                else:
                                    conn.sendall("\nCoordenadas já escolhidas, tente novamente\n".encode('utf-8'))
                            else:
                                conn.sendall("\nComando inválido, tente novamente\n".encode('utf-8'))
                        except ValueError:
                            conn.sendall("\nFormato inválido, use <letra> <número>\n".encode('utf-8'))
                    except ConnectionResetError:
                        print(f"Conexão com {addr} foi resetada.")
                        return

                # Processa o tiro do jogador e atualiza o estado do jogo
                mensagens = ""
                if player['campo'][x][y] == 'S':
                    player['campo_exibicao'][x][y] = 'O'
                    mensagens += "\n==============\nAcertou!\n==============\n"
                    player['acertos'] += 1
                    player['tentativas'] += 1
                else:
                    player['campo_exibicao'][x][y] = 'X'
                    mensagens += "\n==============\nErrou!\n==============\n"
                    player['tentativas'] += 1

                mensagens += exibe_campo(player['campo_exibicao'])
                mensagens += f"Você já tentou: {player['tentativas']} vezes\n"
                try:
                    conn.sendall(mensagens.encode('utf-8'))
                except ConnectionResetError:
                    print(f"Conexão com {addr} foi resetada.")
                    return

                # Passa a vez para o outro jogador
                self.turn = self.other_player_id(player_id)

        # Marca que o jogador terminou o jogo
        self.game_over[player_id] = True

        # Verifica se ambos os jogadores terminaram o jogo e exibe os resultados finais
        if all(self.game_over) and not self.result_displayed:
            self.mostra_resultados()
            self.result_displayed = True  # Marca que os resultados foram exibidos

        # Se o jogador 1 terminou, espere até que o jogador 2 também termine antes de fechar a conexão
        if player_id == 0:
            while not self.game_over[1]:
                time.sleep(1)

        # Espera um pouco antes de fechar a conexão
        time.sleep(2)
        conn.close()

    def mostra_resultados(self):
        """Calcula e exibe os resultados finais do jogo."""
        # Coleta os resultados de cada jogador e os ordena por número de navios derrubados
        resultado_jogadores = [(i, player['acertos']) for i, player in enumerate(self.players)]
        resultado_jogadores.sort(key=lambda x: x[1], reverse=True)

        # Formata e exibe os resultados finais
        resultados = "\n============= Fim de jogo! ==============\n"
        if resultado_jogadores[0][1] > resultado_jogadores[1][1]:
            resultados += f"Jogador {resultado_jogadores[0][0] + 1} venceu!\n"
        else:
            resultados += "\n==============\nEmpate!\n==============\n"

        for player_id, acertos in resultado_jogadores:
            resultados += f"Jogador {player_id + 1} - Navios derrubados: {acertos}\n"

        # Enviar resultados para ambos os jogadores
        for player in self.players:
            try:
                player['conn'].sendall(resultados.encode('utf-8'))
            except OSError as e:
                print(f"Erro ao enviar resultados para {player['addr']}: {e}")