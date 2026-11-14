#!/usr/bin/env python3
"""TLS in front of a node, like tls_relay.py, but one that can keep a transaction to itself. For tests on emulators only.

  tls_gate.py LISTEN_PORT TARGET_PORT CERT KEY FOLDER

While the file FOLDER/keep exists, a transaction handed over (sendrawtransaction) is not passed on. The caller
gets the answer of a gateway whose node is away, and the transaction is added to FOLDER/kept.txt as one line of
hexadecimal text. Every other request goes to the target unchanged, one request per connection.
"""
import json
import os
import socket
import ssl
import sys
import threading

AWAY = '{"result":null,"error":{"code":-32603,"message":"the node behind the gateway did not answer"},"id":"north"}'


def read_message(sock):
    """One HTTP message whose length is given by Content-Length: (head, body), or None if the other side closed first."""
    data = b""
    while b"\r\n\r\n" not in data:
        chunk = sock.recv(65536)
        if not chunk:
            return None
        data += chunk
    head, rest = data.split(b"\r\n\r\n", 1)
    length = 0
    for line in head.split(b"\r\n")[1:]:
        name, _, value = line.partition(b":")
        if name.strip().lower() == b"content-length":
            length = int(value.strip())
    while len(rest) < length:
        chunk = sock.recv(65536)
        if not chunk:
            break
        rest += chunk
    return head, rest[:length]


def answer(sock, status, reason, body):
    raw = body.encode()
    sock.sendall(("HTTP/1.1 %d %s\r\nContent-Type: application/json\r\nContent-Length: %d\r\nConnection: close\r\n\r\n"
                  % (status, reason, len(raw))).encode() + raw)


def serve(client, context, target_port, folder):
    try:
        tls = context.wrap_socket(client, server_side=True)
    except (OSError, ssl.SSLError) as e:
        print("handshake failed: %s" % e, flush=True)
        client.close()
        return
    try:
        message = read_message(tls)
        if message is None:
            return
        head, body = message
        try:
            call = json.loads(body.decode())
            method = call.get("method", "")
        except (ValueError, AttributeError):
            call, method = {}, ""
        if method == "sendrawtransaction" and os.path.exists(os.path.join(folder, "keep")):
            with open(os.path.join(folder, "kept.txt"), "a") as f:
                f.write(str(call.get("params", [""])[0]) + "\n")
            print("kept a transaction", flush=True)
            answer(tls, 502, "Bad Gateway", AWAY)
            return
        lines = [line for line in head.split(b"\r\n") if not line.lower().startswith(b"connection:")]
        upstream = socket.create_connection(("127.0.0.1", target_port), timeout=10)
        upstream.settimeout(180)
        upstream.sendall(b"\r\n".join(lines) + b"\r\nConnection: close\r\n\r\n" + body)
        passed = 0
        while True:
            chunk = upstream.recv(65536)
            if not chunk:
                break
            tls.sendall(chunk)
            passed += len(chunk)
        upstream.close()
        print("%s: %d bytes back" % (method or "?", passed), flush=True)
    except (OSError, ssl.SSLError) as e:
        print("request failed: %s" % e, flush=True)
    finally:
        try:
            tls.close()
        except OSError:
            pass


def main():
    listen_port, target_port, cert, key, folder = int(sys.argv[1]), int(sys.argv[2]), sys.argv[3], sys.argv[4], sys.argv[5]
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(cert, key)
    server = socket.socket()
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", listen_port))
    server.listen(64)
    print("gate 127.0.0.1:%d -> 127.0.0.1:%d, folder %s" % (listen_port, target_port, folder), flush=True)
    while True:
        client, _ = server.accept()
        threading.Thread(target=serve, args=(client, context, target_port, folder), daemon=True).start()


if __name__ == "__main__":
    main()
