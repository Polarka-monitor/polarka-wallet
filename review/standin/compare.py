#!/usr/bin/env python3
"""Asks the stand-in and a real Northbound server the same questions and compares the answers. Reading only.

usage: python3 -B compare.py REAL_URL STAND_IN_URL PAGES < sets.json
sets.json: {"name": ["address", ...], ...}   Every address is public chain data; nothing is sent but addresses.
"""
import json
import sys
import time
import urllib.error
import urllib.request

REAL, MINE, PAGES = sys.argv[1].rstrip("/"), sys.argv[2].rstrip("/"), int(sys.argv[3])
asked = {"real": 0, "mine": 0}


def post(base, path, body):
    asked["real" if base == REAL else "mine"] += 1
    request = urllib.request.Request(base + path, json.dumps(body).encode(), {"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=40) as response:
            return response.status, json.loads(response.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode() or "{}")


def both(path, body_real, body_mine):
    """The same question to both, at the same block: asked again when a block arrived in between."""
    for _ in range(6):
        mine = post(MINE, path, body_mine)
        real = post(REAL, path, body_real)
        if mine[0] == 200 and real[0] == 200 and mine[1]["index"]["indexed_hash"] != real[1]["index"]["indexed_hash"]:
            time.sleep(4)
            continue
        return real, mine
    return real, mine


def kind(line):
    if line["block_height"] is None:
        return "waiting"
    if line["fee"] is not None:
        return "all inputs own"
    if line["received_at"] is not None:
        return "incoming"
    if line["sent_amount"] > 0:
        return "mixed inputs, something sent"
    return "mixed inputs, nothing sent" if line["received_amount"] > 0 else "other"


def first_difference(a, b):
    for i, (x, y) in enumerate(zip(a, b)):
        if x != y:
            return "at %d: real %s | stand-in %s" % (i, json.dumps(x)[:330], json.dumps(y)[:330])
    return "lengths %d and %d" % (len(a), len(b)) if len(a) != len(b) else None


sets = json.load(sys.stdin)
same_everywhere = True
for name, addresses in sets.items():
    print("== %s (%d address%s)" % (name, len(addresses), "" if len(addresses) == 1 else "es"))
    body = {"addresses": addresses}
    real, mine = both("/v1/addresses/activity", body, body)
    ok = real[0] == mine[0] == 200 and real[1]["used_addresses"] == mine[1]["used_addresses"]
    same_everywhere &= ok
    print("   activity: %s (%d used)" % ("same" if ok else "DIFFERENT %s %s" % (real, mine), len(real[1].get("used_addresses", []))))
    real, mine = both("/v1/utxos", body, body)
    ok = real[0] == mine[0] == 200 and real[1]["utxos"] == mine[1]["utxos"]
    same_everywhere &= ok
    count = len(real[1].get("utxos", [])) if real[0] == 200 else -1
    difference = None if ok else (first_difference(real[1].get("utxos", []), mine[1].get("utxos", [])) if real[0] == mine[0] == 200 else "%s | %s" % (real, mine))
    print("   coins: %s (%d coins, tip %s)" % ("same" if ok else "DIFFERENT " + str(difference), count, real[1].get("index", {}).get("indexed_height")))
    # history: every page from each with its own cursor; a block in between starts the set again
    for attempt in range(4):
        lines_real, lines_mine, cursor_real, cursor_mine, tips, restart = [], [], None, None, set(), False
        for page in range(PAGES):
            body_real = dict(body, limit=200, **({"cursor": cursor_real} if cursor_real else {}))
            body_mine = dict(body, limit=200, **({"cursor": cursor_mine} if cursor_mine else {}))
            real, mine = both("/v1/transactions", body_real, body_mine)
            if real[0] != 200 or mine[0] != 200:
                restart = True
                break
            tips.add(real[1]["index"]["indexed_hash"])
            tips.add(mine[1]["index"]["indexed_hash"])
            lines_real += real[1]["transactions"]
            lines_mine += mine[1]["transactions"]
            cursor_real, cursor_mine = real[1]["next_cursor"], mine[1]["next_cursor"]
            if (cursor_real is None) != (cursor_mine is None):
                break
            if cursor_real is None:
                break
        if not restart and len(tips) == 1:
            break
        time.sleep(4)
    waiting_real = sorted(l["transaction_id"] for l in lines_real if l["block_height"] is None)
    waiting_mine = sorted(l["transaction_id"] for l in lines_mine if l["block_height"] is None)
    for line in lines_real + lines_mine:
        if line["block_height"] is None:
            line["timestamp"] = None            # when each of the two first saw a waiting transaction is its own business
    confirmed_real = [l for l in lines_real if l["block_height"] is not None]
    confirmed_mine = [l for l in lines_mine if l["block_height"] is not None]
    n = min(len(confirmed_real), len(confirmed_mine))
    difference = first_difference(confirmed_real[:n], confirmed_mine[:n])
    ended_alike = (cursor_real is None) == (cursor_mine is None) and abs(len(confirmed_real) - len(confirmed_mine)) <= abs(len(waiting_real) - len(waiting_mine))
    ok = difference is None and ended_alike
    same_everywhere &= ok
    kinds = {}
    for line in lines_real:
        kinds[kind(line)] = kinds.get(kind(line), 0) + 1
    print("   history: %s (%d lines in blocks compared, more pages: %s; waiting: real %d, stand-in %d, %s)" % (
        "same" if ok else "DIFFERENT " + str(difference), n, cursor_real is not None, len(waiting_real), len(waiting_mine),
        "same ones" if waiting_real == waiting_mine else "not the same ones"))
    print("   kinds of lines: " + ", ".join("%s %d" % item for item in sorted(kinds.items())))
print("requests: real server %d, stand-in %d" % (asked["real"], asked["mine"]))
print("RESULT: " + ("the stand-in answered like the real server everywhere it was asked" if same_everywhere else "DIFFERENCES FOUND"))
