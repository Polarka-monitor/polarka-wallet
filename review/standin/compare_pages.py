#!/usr/bin/env python3
"""Asks the stand-in and a real Northbound server for pages of coins and of history, and for what they must refuse
about pages, and compares the answers. Reading only.

The two need not stand on the same chain: nothing here depends on what the addresses hold, beyond each of them
holding more than two coins and more than two lines of history. What is compared is the status, the words of a
refusal, the fields of an answer, of a coin, of a line and of a cursor, and how many entries a page has.

usage: python3 -B compare_pages.py REAL_URL REAL_ADDRESS STAND_IN_URL STAND_IN_ADDRESS
The addresses are public chain data of the network each server works for.
"""
import json
import sys
import urllib.error
import urllib.request

REAL, REAL_ADDRESS, MINE, MINE_ADDRESS = sys.argv[1].rstrip("/"), sys.argv[2], sys.argv[3].rstrip("/"), sys.argv[4]
asked = {REAL: 0, MINE: 0}


def post(base, path, body):
    asked[base] += 1
    request = urllib.request.Request(base + path, json.dumps(body).encode(), {"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return response.status, json.loads(response.read().decode())
    except urllib.error.HTTPError as e:
        text = e.read().decode()
        try:
            return e.code, json.loads(text)
        except Exception:
            return e.code, text[:200]


def hexed(value):
    return json.dumps(value, separators=(",", ":")).encode().hex()


def unhexed(text):
    return json.loads(bytes.fromhex(text).decode())


def seen(status, answer, limit=None):
    """What of an answer is compared: the status and, of a refusal, its words; of a page, its fields and its size."""
    if status != 200:
        return status, answer.get("message") if isinstance(answer, dict) else answer
    entries = answer.get("utxos", answer.get("transactions"))
    cursor = answer.get("next_cursor")
    return (status, sorted(answer), sorted(entries[0]) if entries else None, len(entries) if limit is not None else None,
            sorted(unhexed(cursor)) if cursor else None, sorted(answer["index"]))


def first_page(base, address, path):
    status, answer = post(base, path, {"addresses": [address], "limit": 2})
    if status != 200 or not answer.get("next_cursor"):
        raise SystemExit("%s %s: the address must hold more than two entries (%s %s)" % (base, path, status, str(answer)[:200]))
    return answer


results = []
cursors = {}


def compare(name, path, body_of, limit=None, fresh=False):
    """
    [body_of] makes the request for one of the two from its address and from the cursor of its own first page.
    That page is asked for once and its cursor kept, except where the cursor has to hold ([fresh]): a block
    that arrives while this runs would make a kept one stale.
    """
    answers = []
    for base, address in ((REAL, REAL_ADDRESS), (MINE, MINE_ADDRESS)):
        if fresh or (base, path) not in cursors:
            cursors[(base, path)] = unhexed(first_page(base, address, path)["next_cursor"])
        cursor = cursors[(base, path)]
        answers.append(seen(*post(base, path, body_of(address, cursor)), limit=limit))
    same = answers[0] == answers[1]
    results.append(same)
    print("%-62s %s  real %s%s" % (name, "same     " if same else "DIFFERENT", json.dumps(answers[0])[:260],
                                 "" if same else "  | stand-in %s" % json.dumps(answers[1])[:260]))


def changed(cursor, **fields):
    return hexed(dict(cursor, **fields))


def less(cursor, name):
    return hexed({k: v for k, v in cursor.items() if k != name})


for path, what, height in (("/v1/utxos", "coins", "block_height"), ("/v1/transactions", "history", "height")):
    compare("%s: a page of two" % what, path, lambda a, c: {"addresses": [a], "limit": 2}, limit=2)
    compare("%s: the page after it" % what, path, lambda a, c: {"addresses": [a], "limit": 2, "cursor": hexed(c)}, limit=2, fresh=True)
    compare("%s: no limit named" % what, path, lambda a, c: {"addresses": [a]})
    compare("%s: cursor given as null" % what, path, lambda a, c: {"addresses": [a], "limit": 2, "cursor": None}, limit=2)
    compare("%s: limit 0" % what, path, lambda a, c: {"addresses": [a], "limit": 0})
    compare("%s: limit one above the most" % what, path, lambda a, c: {"addresses": [a], "limit": 10001 if what == "coins" else 201})
    compare("%s: limit 65536" % what, path, lambda a, c: {"addresses": [a], "limit": 65536})
    compare("%s: limit below zero" % what, path, lambda a, c: {"addresses": [a], "limit": -1})
    compare("%s: limit as text" % what, path, lambda a, c: {"addresses": [a], "limit": "2"})
    compare("%s: limit with a fraction" % what, path, lambda a, c: {"addresses": [a], "limit": 1.5})
    compare("%s: a cursor that is a number" % what, path, lambda a, c: {"addresses": [a], "cursor": 5})
    compare("%s: a cursor that is not hexadecimal" % what, path, lambda a, c: {"addresses": [a], "cursor": "zz"})
    compare("%s: a cursor of odd length" % what, path, lambda a, c: {"addresses": [a], "cursor": "abc"})
    compare("%s: a cursor of 1,026 characters" % what, path, lambda a, c: {"addresses": [a], "cursor": "ab" * 513})
    compare("%s: a cursor that is a list" % what, path, lambda a, c: {"addresses": [a], "cursor": hexed([1])})
    compare("%s: a cursor without its tip" % what, path, lambda a, c: {"addresses": [a], "cursor": less(c, "tip_hash")})
    compare("%s: a cursor whose revision is text" % what, path, lambda a, c: {"addresses": [a], "cursor": changed(c, pending_revision="1")})
    compare("%s: a cursor with a height that is text" % what, path, lambda a, c: {"addresses": [a], "cursor": changed(c, **{height: "1"})})
    compare("%s: a cursor with a height below zero" % what, path, lambda a, c: {"addresses": [a], "cursor": changed(c, **{height: -1})})
    compare("%s: a cursor with a tip that is no id" % what, path, lambda a, c: {"addresses": [a], "cursor": changed(c, tip_hash="nothing")})
    compare("%s: a cursor of another revision" % what, path, lambda a, c: {"addresses": [a], "limit": 2, "cursor": changed(c, pending_revision=c["pending_revision"] + 1)})
    compare("%s: a cursor of another tip" % what, path, lambda a, c: {"addresses": [a], "limit": 2, "cursor": changed(c, tip_hash="00" * 32)})
    compare("%s: a cursor with a field too many" % what, path, lambda a, c: {"addresses": [a], "limit": 2, "cursor": changed(c, extra=1)}, limit=2, fresh=True)
compare("coins: a cursor with a height of 2 to the 63rd", "/v1/utxos", lambda a, c: {"addresses": [a], "cursor": changed(c, block_height=1 << 63)})
compare("coins: a cursor with an output number below zero", "/v1/utxos", lambda a, c: {"addresses": [a], "cursor": changed(c, output_index=-1)})
compare("coins: a cursor with an output number of 2 to the 32nd", "/v1/utxos", lambda a, c: {"addresses": [a], "cursor": changed(c, output_index=1 << 32)})
compare("coins: a cursor with an id that is no id", "/v1/utxos", lambda a, c: {"addresses": [a], "cursor": changed(c, transaction_id="ab")})
compare("history: a cursor with an id that is no id", "/v1/transactions", lambda a, c: {"addresses": [a], "cursor": changed(c, txid="ab")})
print("requests: real server %d, stand-in %d" % (asked[REAL], asked[MINE]))
print("%d of %d alike" % (results.count(True), len(results)))
