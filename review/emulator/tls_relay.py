#!/usr/bin/env python3
"""TLS in front of a local plain service, for tests on emulators only.

  tls_relay.py LISTEN_PORT TARGET_PORT CERT KEY

Listens on 127.0.0.1 only (an emulator reaches it as 10.0.2.2) and passes bytes both ways unchanged. It writes
one line "connection LISTEN_PORT" for every connection it takes: how often a client opens a new one is how well
that client keeps its connections.
"""
import socket
import ssl
import sys
import threading


def pump(src, dst):
    try:
        while True:
            data = src.recv(65536)
            if not data:
                break
            dst.sendall(data)
    except (OSError, ssl.SSLError):
        pass
    finally:
        for s in (src, dst):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                s.close()
            except OSError:
                pass


def serve(client, context, target_port, listen_port):
    try:
        tls = context.wrap_socket(client, server_side=True)
    except (OSError, ssl.SSLError) as e:
        print("handshake failed: %s" % e, flush=True)
        client.close()
        return
    try:
        upstream = socket.create_connection(("127.0.0.1", target_port), timeout=10)
        upstream.settimeout(None)
    except OSError as e:
        print("target unreachable: %s" % e, flush=True)
        tls.close()
        return
    print("connection %d" % listen_port, flush=True)
    threading.Thread(target=pump, args=(tls, upstream), daemon=True).start()
    pump(upstream, tls)


def main():
    listen_port, target_port, cert, key = int(sys.argv[1]), int(sys.argv[2]), sys.argv[3], sys.argv[4]
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(cert, key)
    server = socket.socket()
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", listen_port))
    server.listen(64)
    print("relay 127.0.0.1:%d -> 127.0.0.1:%d" % (listen_port, target_port), flush=True)
    while True:
        client, _ = server.accept()
        threading.Thread(target=serve, args=(client, context, target_port, listen_port), daemon=True).start()


if __name__ == "__main__":
    main()
