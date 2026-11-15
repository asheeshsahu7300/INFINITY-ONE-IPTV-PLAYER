import socket
import threading
import sys

def forward(src, dst):
    try:
        while True:
            data = src.recv(4096)
            if not data:
                break
            dst.sendall(data)
    except Exception:
        pass
    finally:
        try:
            src.close()
        except Exception:
            pass
        try:
            dst.close()
        except Exception:
            pass

def handle_client(client_sock, target_host, target_port):
    try:
        target_sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        target_sock.connect((target_host, target_port))
        t1 = threading.Thread(target=forward, args=(client_sock, target_sock), daemon=True)
        t2 = threading.Thread(target=forward, args=(target_sock, client_sock), daemon=True)
        t1.start()
        t2.start()
    except Exception as e:
        client_sock.close()

def main():
    listen_port = 8765
    target_host = "127.0.0.1"
    target_port = 8766
    
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("0.0.0.0", listen_port))
    server.listen(10)
    print(f"LAN Bridge listening on 0.0.0.0:{listen_port} -> {target_host}:{target_port}", flush=True)

    while True:
        client_sock, addr = server.accept()
        threading.Thread(target=handle_client, args=(client_sock, target_host, target_port), daemon=True).start()

if __name__ == "__main__":
    main()
