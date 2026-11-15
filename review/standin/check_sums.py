#!/usr/bin/env python3
"""Checks on a Northbound server that what an address holds is what its history adds up to. Reading only.

A line of history says what a transaction paid to the addresses of the request less what it took from them
(net_amount). If that is what it means, the lines of an address that are in blocks add up to the coins the
address holds, when both are read from the same picture of the chain and no waiting transaction spends from
the address. This asks for both, page by page, and compares.

usage: python3 -B check_sums.py SERVER_URL [MOST_LINES] < addresses.txt      (one address to a line)
Every address is asked about by itself. An address whose history is longer than MOST_LINES (2,000 unless
named) is passed over. The addresses are public chain data; nothing is sent but addresses.
"""
import json
import sys
import time
import urllib.error
import urllib.request

SERVER = sys.argv[1].rstrip("/")
MOST = int(sys.argv[2]) if len(sys.argv) > 2 else 2000
asked = 0


class Changed(Exception):
    pass


def post(path, body):
    global asked
    asked += 1
    request = urllib.request.Request(SERVER + path, json.dumps(body).encode(), {"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.loads(response.read().decode())
    except urllib.error.HTTPError as e:
        if e.code == 409:
            raise Changed()
        raise SystemExit("%s answered %d: %s" % (path, e.code, e.read().decode()[:200]))


def pages(path, address, name, limit, most):
    """Every entry, and the picture they were read from: the tip and the server's number for what was waiting. None when there are more than [most]."""
    entries, cursor, picture = [], None, None
    while True:
        answer = post(path, {"addresses": [address], "limit": limit, "cursor": cursor})
        now = (answer["index"]["indexed_hash"], answer["index"].get("pending_revision"))
        if picture is not None and now != picture:
            raise Changed()
        picture = now
        entries += answer[name]
        cursor = answer["next_cursor"]
        if cursor is None:
            return entries, picture
        if len(entries) >= most:
            return None, picture


checked = alike = 0
for number, address in enumerate(line.strip() for line in sys.stdin if line.strip()):
    for attempt in range(4):
        try:
            lines, at = pages("/v1/transactions", address, "transactions", 200, MOST)
            if lines is None:
                break
            coins, then = pages("/v1/utxos", address, "utxos", 1000, 100_000)
            if then != at:
                raise Changed()
            break
        except Changed:
            time.sleep(3)
    else:
        print("address %d: the chain kept changing, not checked" % (number + 1))
        continue
    if lines is None:
        print("address %d: a history of more than %d lines, passed over" % (number + 1, MOST))
        continue
    waiting = [l for l in lines if l["block_height"] is None]
    in_blocks = [l for l in lines if l["block_height"] is not None]
    rewards = [l for l in in_blocks if l["network_fee"] is None]
    total = sum(l["net_amount"] for l in in_blocks)
    held = sum(c["amount"] for c in coins)
    spending = [l for l in waiting if l["net_amount"] < 0]
    same = total == held and not spending
    checked += 1
    alike += same
    print("address %d: %d lines in blocks (%d rewards, %d gains, %d losses), %d waiting; they add up to %d; %d coins hold %d: %s" % (
        number + 1, len(in_blocks), len(rewards), sum(1 for l in in_blocks if l["network_fee"] is not None and l["net_amount"] > 0),
        sum(1 for l in in_blocks if l["net_amount"] < 0), len(waiting), total, len(coins), held,
        "the same" if same else ("a waiting transaction spends from it, so the two cannot be compared" if spending else "DIFFERENT")))
    odd = [l for l in rewards if l["net_amount"] < 0] + [l for l in lines if l["network_fee"] is not None and l["network_fee"] < 0]
    if odd:
        print("   lines that cannot be: %s" % json.dumps(odd)[:300])
print("requests: %d. %d of %d addresses checked hold what their history adds up to." % (asked, alike, checked))
