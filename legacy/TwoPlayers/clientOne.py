import socket

# HOST = '127.0.0.1' #LocalHost
# HOST = '192.168.72.1'
HOST = '192.168.72.3'
PORT = 8080

def main():

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.connect((HOST, PORT))
        print("Conectado ao servidor.\n")

        while True:
            data = s.recv(1024).decode('utf-8')
            print(data)

            if "Sua vez" in data:
                inp = input()
                s.sendall(inp.encode('utf-8'))

            if "Fim de jogo" in data:
                break

if __name__ == "__main__":
    main()
