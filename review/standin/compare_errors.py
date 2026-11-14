#!/usr/bin/env python3
"""Asks the stand-in and a real Northbound server for things they must refuse, and compares status and message.

Reading only: nothing here can be passed on to the network.

usage: python3 -B compare_errors.py REAL_URL STAND_IN_URL ADDRESS TXID
ADDRESS is any address of the servers' network, TXID any transaction that is in a block.
"""
import json
import sys
import urllib.error
import urllib.request

REAL, MINE = sys.argv[1].rstrip("/"), sys.argv[2].rstrip("/")
GOOD, KNOWN = sys.argv[3], sys.argv[4]
# an address of the other network, from the usual test phrase: its first address on regtest, its third on the main network
ELSEWHERE = "rnorth1zfgvaxg6parqfv2drvsk9zadefh40zg97ese44hjkjzkjamkftpzsnpx4h6" if GOOD.startswith("north1") else "north1zk3jwu52pe3h5sendhwpm7e6hlfs9657zltfrvxfqee4gxlgj25usu60dh3"


def ask(base, method, path, body=None, content_type="application/json"):
    data = None if body is None else (body if isinstance(body, bytes) else json.dumps(body).encode())
    headers = {} if data is None or content_type is None else {"Content-Type": content_type}
    request = urllib.request.Request(base + path, data, headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=40) as response:
            text, status, retry = response.read().decode(), response.status, response.headers.get("Retry-After")
    except urllib.error.HTTPError as e:
        text, status, retry = e.read().decode(), e.code, e.headers.get("Retry-After")
    try:
        answer = json.loads(text)
    except Exception:
        answer = text[:200]
    if isinstance(answer, dict) and "index" in answer:
        answer = {k: v for k, v in answer.items() if k != "index"}
    return status, answer


CASES = [
    ("101 addresses", "POST", "/v1/utxos", {"addresses": [GOOD] * 101}, "application/json"),
    ("an address that is not one", "POST", "/v1/utxos", {"addresses": ["north1zzz"]}, "application/json"),
    ("an address of another network", "POST", "/v1/utxos", {"addresses": [ELSEWHERE]}, "application/json"),
    ("no addresses field", "POST", "/v1/utxos", {}, "application/json"),
    ("not JSON", "POST", "/v1/utxos", b"nonsense", "application/json"),
    ("no content type", "POST", "/v1/utxos", {"addresses": [GOOD]}, None),
    ("history limit 0", "POST", "/v1/transactions", {"addresses": [GOOD], "limit": 0}, "application/json"),
    ("history limit 201", "POST", "/v1/transactions", {"addresses": [GOOD], "limit": 201}, "application/json"),
    ("history limit as text", "POST", "/v1/transactions", {"addresses": [GOOD], "limit": "2"}, "application/json"),
    ("a cursor that is not one", "POST", "/v1/transactions", {"addresses": [GOOD], "cursor": "zz"}, "application/json"),
    ("status of an id that is not one", "POST", "/v1/transactions/status", {"transaction_id": "abc"}, "application/json"),
    ("status of a transaction nobody knows", "POST", "/v1/transactions/status", {"transaction_id": "00" * 32}, "application/json"),
    ("status of a transaction in a block", "POST", "/v1/transactions/status", {"transaction_id": KNOWN}, "application/json"),
    ("a transaction that is not hexadecimal, to be checked", "POST", "/v1/transactions/validate", {"transaction_hex": "zz"}, "application/json"),
    ("a path that is not there", "GET", "/v1/nothing", None, None),
    ("GET where POST is meant", "GET", "/v1/utxos", None, None),
    ("empty address list", "POST", "/v1/utxos", {"addresses": []}, "application/json"),
    ("empty address list, history", "POST", "/v1/transactions", {"addresses": []}, "application/json"),
]
alike = 0
for name, method, path, body, content_type in CASES:
    real = ask(REAL, method, path, body, content_type)
    mine = ask(MINE, method, path, body, content_type)
    same = real == mine
    alike += same
    print("%-52s %s  real %s %s%s" % (name, "same     " if same else "DIFFERENT", real[0], json.dumps(real[1])[:150],
                                     "" if same else "  | stand-in %s %s" % (mine[0], json.dumps(mine[1])[:150])))
print("%d of %d alike" % (alike, len(CASES)))
