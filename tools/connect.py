import socket
import sys

host = sys.argv[1] if len(sys.argv) > 1 else "rabbit"
port = int(sys.argv[2]) if len(sys.argv) > 2 else 9100

print(f"Connecting to {host}:{port}...")
sock = socket.create_connection((host, port), timeout=5)
data = sock.recv(1024).decode()
print(data, end="")
sock.close()
