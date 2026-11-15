#!/usr/bin/env python3
"""Asks the bench's wallet server a few questions over TLS from this machine, the way the emulator will.

  BENCH=N probe_server.py

A check of the bench itself, without a phone: the stand-in answers through the relay, the test switches work,
and the count of connections and requests moves.
"""
import http.client
import json
import os
import ssl
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
NAME = os.environ.get("BENCH", "")
with open(os.path.join(HERE, "run" + NAME, "current.json")) as f:
    st = json.load(f)
port = int(st["server_url"].rsplit(":", 1)[1])
context = ssl.create_default_context(cafile=os.path.join(HERE, "tls", "ca.pem"))
context.check_hostname = False          # the certificate names 10.0.2.2, which only an emulator can call


def bench(*args):
    r = subprocess.run([sys.executable, "-B", os.path.join(HERE, "bench.py")] + list(args), capture_output=True, text=True, timeout=120)
    if r.returncode:
        raise SystemExit("bench %s: %s" % (args[0], (r.stderr or r.stdout).strip()[:300]))
    return r.stdout.strip()


def ask(connection, method, path, body=None):
    data = None if body is None else json.dumps(body).encode()
    connection.request(method, path, data, {"Content-Type": "application/json"} if data else {})
    answer = connection.getresponse()
    return answer.status, json.loads(answer.read().decode())


before = json.loads(bench("seen"))
one = http.client.HTTPSConnection("127.0.0.1", port, context=context, timeout=30)
print("tip        ", ask(one, "GET", "/v1/chain/tip"))
print("fee        ", ask(one, "GET", "/v1/fees/estimate"))
status, coins = ask(one, "POST", "/v1/utxos", {"addresses": [st["bench_address"]]})
print("coins      ", status, len(coins.get("utxos", [])), "coins, index at", coins.get("index", {}).get("indexed_height"))
# a valid address of another network altogether: a test vector of BIP-350
print("other net  ", ask(one, "POST", "/v1/utxos", {"addresses": ["bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0"]}))
print("switch     ", bench("standin", '{"busy": 2}'))
print("busy       ", [ask(one, "GET", "/v1/chain/tip")[0] for _ in range(3)])
print("switch     ", bench("standin", '{"stale": true}'))
print("stale      ", ask(one, "POST", "/v1/utxos", {"addresses": [st["bench_address"]]}))
print("switch     ", bench("standin", '{"stale": false}'))
print("fresh again", ask(one, "POST", "/v1/utxos", {"addresses": [st["bench_address"]]})[0])
one.close()
two = http.client.HTTPSConnection("127.0.0.1", port, context=context, timeout=30)
print("second conn", ask(two, "GET", "/v1/chain/tip"))
two.close()
after = json.loads(bench("seen"))
print("seen before", before)
print("seen after ", after)
path = bench("setup", "server")
print("setup file ", open(path).read())
path = bench("setup", "server", st["bench_address"])
print("with watch ", open(path).read())
