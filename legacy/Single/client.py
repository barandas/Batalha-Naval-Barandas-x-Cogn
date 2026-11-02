import socket

SIZE = 15
HOST = '127.0.0.1'
# HOST = '172.26.11.205' # IP WIFI de Flávia
# HOST = '172.26.10.211' # IP WIFI de Isaac
PORT = 8080

def main():
    client = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    client.connect((HOST, PORT))

    print("Digite 'sair' para encerrar a conexão.")

    while True:
        data = client.recv(1024).decode('utf-8')
        print(data)

        if "Fim de jogo" in data:
            break

        while True:
            letra = input("Digite a letra: ")
            if letra.lower() == 'sair':
                print("Encerrando a conexão...")
                client.sendall(letra.encode('utf-8'))
                return
            if letra.upper() < 'A' or letra.upper() > chr(ord('A') + SIZE - 1):
                print("\nCoordenada inválida, tente novamente.\n")
                continue

            numero = input("Digite o número: ")
            if numero.lower() == 'sair':
                print("Encerrando a conexão...")
                client.sendall(numero.encode('utf-8'))
                return
            if not numero.isdigit() or int(numero) < 1 or int(numero) > SIZE:
                print("\nCoordenada inválida, tente novamente.\n")
                continue

            client.sendall(f"{letra} {numero}".encode('utf-8'))
            break

    client.close()

if __name__ == "__main__":
    main()