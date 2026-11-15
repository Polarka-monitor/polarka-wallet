#!/usr/bin/env python3
"""Tries the two switches of the stand-in that play a server which is not honest, from this computer and
without a phone: BENCH=9 python3 -B probe_says_no.py (the bench must be up). Nothing is handed to the node."""
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))


def bench(*args):
    r = subprocess.run([sys.executable, "-B", os.path.join(HERE, "bench.py")] + [str(a) for a in args],
                       capture_output=True, text=True, timeout=180)
    if r.returncode:
        raise SystemExit("bench %s: %s" % (args[:2], (r.stderr or r.stdout).strip()[:300]))
    return r.stdout.strip()


def ask(path, body):
    request = urllib.request.Request("http://127.0.0.1:%d%s" % (port, path), json.dumps(body).encode(),
                                     {"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.status, json.loads(response.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode())


def check(condition, what):
    print(("ok    " if condition else "WRONG ") + what, flush=True)
    if not condition:
        raise SystemExit(1)


port = json.loads(bench("info"))["standin_port"]
to = bench("call", "wallet=bench", "getnewaddress")
raw = bench("call", "createrawtransaction", "[]", json.dumps({to: 1}))
funded = json.loads(bench("call", "wallet=bench", "fundrawtransaction", raw))["hex"]
signed = json.loads(bench("call", "wallet=bench", "signrawtransactionwithwallet", funded))["hex"]
tx = json.loads(bench("call", "decoderawtransaction", signed))
first = tx["vin"][0]
before = json.loads(bench("call", "gettxout", first["txid"], first["vout"]))      # the node keeps no index of transactions
owner = before["scriptPubKey"]["address"]
coin = (first["txid"], first["vout"])


def listed():
    status, answer = ask("/v1/utxos", {"addresses": [owner]})
    return status == 200 and coin in [(c["transaction_id"], c["output_index"]) for c in answer["utxos"]]


start = json.loads(bench("standin", "{}"))
check(start["refuse"] == "" and start["hide"] is False, "both switches are off when nothing was set: %s" % {k: start[k] for k in ("refuse", "hide")})
already = len(start["kept"])
check(ask("/v1/transactions/validate", {"transaction_hex": signed}) == (200, {"allowed": True, "rejection_reason": None}),
      "an honest stand-in asks its node, and the node would take the transaction")
check(listed(), "the coin of the transaction is listed")
bench("standin", json.dumps({"refuse": "min relay fee not met"}))
check(ask("/v1/transactions/validate", {"transaction_hex": signed}) == (200, {"allowed": False, "rejection_reason": "min relay fee not met"}),
      "with the switch the stand-in says that the node does not take it, in the words that were set")
status, answer = ask("/v1/transactions/broadcast", {"transaction_hex": signed})
check(status == 502, "broadcast is answered with the node's error (%d: %s)" % (status, str(answer)[:90]))
check(tx["txid"] not in bench("call", "getrawmempool"), "the node was handed nothing")
kept = json.loads(bench("standin", "{}"))["kept"]
check(kept[already:] == [signed], "the transaction is kept once, though it was shown twice")
check(listed(), "its coin is still listed while nothing is hidden")
bench("standin", json.dumps({"hide": True}))
check(not listed(), "with the second switch the coin is left out of the list")
back = json.loads(bench("standin", json.dumps({"refuse": "", "hide": False})))
check(back["refuse"] == "" and back["hide"] is False and back["kept"][already:] == [signed], "both switches are off again, and what was kept stays kept")
check(listed(), "the coin is listed again")
check(ask("/v1/transactions/validate", {"transaction_hex": signed}) == (200, {"allowed": True, "rejection_reason": None}),
      "and the stand-in asks its node again")
print("THE TWO SWITCHES DO WHAT THEY SAY")
