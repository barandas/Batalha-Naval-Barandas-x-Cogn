import socket
import threading
import random
import time

SIZE = 15
NUM_NAVIOS = 10
MAX_TENT = 20

def inicializa_campo():
    return [[' ' for _ in range(SIZE)] for _ in range(SIZE)]

def coloca_navios(campo):
    navios_colocados = 0
    coordenadas_ocupadas = set()
    
    while navios_colocados < NUM_NAVIOS:
        x = random.randint(0, SIZE - 1)
        y = random.randint(0, SIZE - 1)
        coordenada = (x, y)
        
        if coordenada not in coordenadas_ocupadas:
            campo[x][y] = 'S'
            coordenadas_ocupadas.add(coordenada)
            navios_colocados += 1
            
    return campo
        

def exibe_campo(campo):
    header = "    " + " ".join([chr(c + ord('A')) for c in range(SIZE)]) + "\n"
    display = header
    for i in range(SIZE):
        row_number = f"{i + 1:2}"
        row_content = row_number.rjust(2) + "  " + " ".join(campo[i])
        display += row_content + "\n"
    return display

def handle_client(conn, addr):
    print(f"Conexão estabelecida com {addr}")
    conn.sendall("\nBem-vindo ao jogo Batalha Naval!\n".encode('utf-8'))

    campo_cliente = inicializa_campo()
    campo_exibicao_cliente = inicializa_campo()
    coloca_navios(campo_cliente)

    tentativas_cliente = 0
    acertos_cliente = 0
    coordenadas_usadas_cliente = set()

    while tentativas_cliente < MAX_TENT  and acertos_cliente < NUM_NAVIOS:
        while True:
            data = conn.recv(1024).decode('utf-8')
            if not data or data.lower() == 'sair':
                conn.close()
                return
            try:
                letra, numero = data.split()
                x = int(numero) - 1
                y = ord(letra.upper()) - ord('A')
                if 0 <= x < SIZE and 0 <= y < SIZE:
                    if (x, y) not in coordenadas_usadas_cliente:
                        coordenadas_usadas_cliente.add((x, y))
                        break
                    else:
                        conn.sendall("\nCoordenadas já escolhidas, tente novamente\n".encode('utf-8'))
                else:
                    conn.sendall("\nComando inválido, tente novamente\n".encode('utf-8'))
            except ValueError:
                conn.sendall("\nFormato inválido, use <letra> <número>\n".encode('utf-8'))

        mensagens = ""
        if campo_cliente[x][y] == 'S':
            campo_exibicao_cliente[x][y] = 'O'
            mensagens += "\n============"
            mensagens += "\nAcertou!\n"
            mensagens += "============\n"
            acertos_cliente += 1
            tentativas_cliente += 1
        else:
            campo_exibicao_cliente[x][y] = 'X'
            mensagens += "\n============"
            mensagens += "\nErrou!\n"
            mensagens += "============\n"
            tentativas_cliente += 1
        
        mensagens += exibe_campo(campo_exibicao_cliente)
        mensagens += f"Você já tentou: {tentativas_cliente} vezes\n"
        conn.sendall(mensagens.encode('utf-8'))
        print(tentativas_cliente)

    mensagens = "\nFim de jogo!\n"
    mensagens += f"\nNavios derrubados pelo cliente: {acertos_cliente}\n"
    mensagens += "\nCampo do cliente:\n\n"
    mensagens += exibe_campo(campo_exibicao_cliente)
    mensagens += "\nCampo completo:\n\n"
    mensagens += exibe_campo(campo_cliente)
    conn.sendall(mensagens.encode('utf-8'))
    time.sleep(4)
    conn.close()

def main():
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.bind(('0.0.0.0', 8080))
    server.listen(1)
    print("Servidor iniciado na porta 8080")
    while True:
        conn, addr = server.accept()
        threading.Thread(target=handle_client, args=(conn, addr)).start()

if __name__ == "__main__":
    main()