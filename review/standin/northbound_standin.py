#!/usr/bin/env python3
"""A stand-in for a Northbound wallet server (API version 1), for tests.

It takes the same requests and answers in the same shapes as the server of github.com/nova9/northbound, from
a TrueNorth Core node. It was written from that server's sources (tag v0.1.0-rc.5 and the commits up to
07523b6 of 15 November 2026, which brought coins in pages and history lines as signed amounts). None of that
code is used or run here, and where this program and the real server differ the real server is right.

It keeps no database: every answer is worked out from the node's blocks and waiting transactions as they
are at that moment. It listens on 127.0.0.1 only, trusts its node, and is not meant to serve anybody.

  northbound_standin.py --rpc-url http://127.0.0.1:PORT --cookie DATADIR/regtest/.cookie [--port 0] [--warm]

It prints "listening PORT" when it is ready. With --warm it reads the whole chain before that.

Beside the API it takes POST /__standin/set with a JSON object:
  {"stale": true}   the index says it is not up to date, and what is read from it is answered 503
  {"busy": N}       the next N requests are answered 429 with Retry-After: 1
  {"hold": true}    a transaction handed over for broadcast is not passed on; the answer is the node's error, 502
  {"refuse": "WHY"} the server says no and keeps what it was shown: validation answers that the node does not
                    take the transaction (WHY), broadcast answers the node's error, the node is asked neither
                    time, and the transaction is kept. "" turns it off
  {"hide": true}    the coins that a kept transaction spends are left out of /v1/utxos
  {"page": N}       /v1/utxos gives N coins to a page when the request names no limit (0: a thousand, as the
                    real server does)
The answer names every switch, and under "kept" the transactions that a server which said no was shown. The
real server has no such switches. The last two play a server that is not honest, which the real one is not
known to be: a wallet has to stay safe with one all the same.
"""
import argparse
import base64
import json
import sys
import threading
import time
import urllib.error
import urllib.request
from decimal import Decimal
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

COIN = 100_000_000
MAX_ADDRESSES = 100
UTXO_PAGE = 1_000                 # coins on a page when the request names no limit
MAX_UTXO_PAGE = 10_000
MAX_CURSOR = 1_024
MAX_BODY = 1_000_000
MAX_HEX = 800_000
MAX_AGE_SECONDS = 30
NO_BLOCK = (1 << 63) - 1          # where the real server sorts a waiting transaction: above every block
NODE_ERROR = "The blockchain node is unavailable or returned an invalid response."
CHANGED = "The index changed; restart pagination."


class Answer(Exception):
    """An answer that ends the request: an HTTP status and a message, as the real server words it."""

    def __init__(self, status, message, headers=None):
        super().__init__(message)
        self.status = status
        self.message = message
        self.headers = headers or {}


class Refused(Exception):
    """The node answered a call with an error of its own."""

    def __init__(self, code, message):
        super().__init__("%s (%s)" % (message, code))
        self.code = code


class NodeDown(Exception):
    pass


def units(value):
    """An amount as the node prints it (coins, a decimal number) in base units, exactly."""
    scaled = Decimal(str(value)).scaleb(8)
    whole = int(scaled)
    if scaled != whole or whole < 0:
        raise NodeDown("an amount that is not a whole number of base units: %s" % value)
    return whole


class Node:
    def __init__(self, url, cookie):
        self.url = url
        self.cookie = cookie

    def call(self, method, *params):
        with open(self.cookie, encoding="utf-8") as f:      # read every time: the node writes a new one whenever it starts
            login = f.read().strip()
        body = json.dumps({"jsonrpc": "1.0", "id": "standin", "method": method, "params": list(params)}).encode()
        request = urllib.request.Request(self.url, body, {
            "Content-Type": "application/json",
            "Authorization": "Basic " + base64.b64encode(login.encode()).decode(),
        })
        try:
            try:
                with urllib.request.urlopen(request, timeout=120) as response:
                    text = response.read().decode()
            except urllib.error.HTTPError as e:
                text = e.read().decode()
            answer = json.loads(text, parse_float=Decimal)
        except Exception as e:                                # nothing listens, a timeout, no JSON
            raise NodeDown("%s: %s" % (method, e))
        if answer.get("error"):
            raise Refused(answer["error"].get("code"), answer["error"].get("message"))
        return answer["result"]


class Chain:
    """What is in blocks: every output, what spends it, and where each transaction is."""

    def __init__(self):
        self.hashes = []            # the block hash at every height
        self.outputs = {}           # (txid, n) -> (amount, script, height)
        self.where = {}             # txid -> (height, block time, is a block reward)
        self.spends = {}            # (txid, n) -> the transaction that spends it
        self.inputs = {}            # txid -> [(txid, n), ...]
        self.count = {}             # txid -> number of outputs
        self.by_script = {}         # script -> [(txid, n), ...]

    def add(self, block):
        height = len(self.hashes)
        self.hashes.append(block["hash"])
        for tx in block["txs"]:
            txid = tx["txid"]
            self.where[txid] = (height, block["time"], tx["coinbase"])
            self.inputs[txid] = tx["inputs"]
            for spent in tx["inputs"]:
                self.spends[spent] = txid
            self.count[txid] = len(tx["outputs"])
            for n, (amount, script) in enumerate(tx["outputs"]):
                self.outputs[(txid, n)] = (amount, script, height)
                self.by_script.setdefault(script, []).append((txid, n))


def is_id(text):
    return isinstance(text, str) and len(text) == 64 and all(c in "0123456789abcdefABCDEF" for c in text)


HRPS = {"main": "north", "regtest": "rnorth"}
CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"


def bech32_checksum_holds(address):
    """True if the text is a Bech32 or Bech32m string with a right checksum (BIP-173, BIP-350). What it encodes is not looked at."""
    text = address.lower()
    if address not in (text, address.upper()) or "1" not in text:
        return False
    hrp, _, data = text.rpartition("1")
    if not hrp or len(data) < 6 or any(c not in CHARSET for c in data):
        return False
    check = 1
    for value in [ord(c) >> 5 for c in hrp] + [0] + [ord(c) & 31 for c in hrp] + [CHARSET.find(c) for c in data]:
        top = check >> 25
        check = (check & 0x1ffffff) << 5 ^ value
        for i, generator in enumerate((0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)):
            if (top >> i) & 1:
                check ^= generator
    return check in (1, 0x2bc830a3)


def parsed(tx):
    """A transaction as the node shows it (getblock 2, getrawtransaction true), reduced to what is needed here."""
    coinbase = any("coinbase" in i for i in tx["vin"])
    inputs = [] if coinbase else [(i["txid"], i["vout"]) for i in tx["vin"]]
    outputs = [(units(o["value"]), o["scriptPubKey"]["hex"]) for o in sorted(tx["vout"], key=lambda o: o["n"])]
    return {"txid": tx["txid"], "coinbase": coinbase, "inputs": inputs, "outputs": outputs}


def whole(value, low, high):
    """True for a whole number in a range. JSON's true and false are not numbers here."""
    return isinstance(value, int) and not isinstance(value, bool) and low <= value <= high


def cursor_of(text, what, numbers, ids):
    """
    A cursor as the real server reads it: hexadecimal text of a JSON object. What does not parse into the
    fields it must have is "invalid"; fields that parse and cannot be are "invalid ... fields".
    """
    if len(text) > MAX_CURSOR:
        raise Answer(400, "%s cursor is too long" % what)
    try:
        if len(text) % 2 or any(c not in "0123456789abcdefABCDEF" for c in text):
            raise ValueError("not hexadecimal")
        cursor = json.loads(bytes.fromhex(text).decode())
        typed = isinstance(cursor, dict) and all(whole(cursor[name], low, high) for name, (low, high) in numbers.items()) \
            and all(isinstance(cursor[name], str) for name in ids)
    except Exception:
        typed = False
    if not typed:
        raise Answer(400, "invalid %s cursor" % what)
    return cursor


def read_cursor(text):
    """The cursor of a page of history: two signed numbers of 64 bits, and two ids."""
    span = (-(1 << 63), (1 << 63) - 1)
    cursor = cursor_of(text, "history", {"pending_revision": span, "height": span}, ("txid", "tip_hash"))
    if cursor["height"] < 0 or not is_id(cursor["txid"]) or not is_id(cursor["tip_hash"]):
        raise Answer(400, "invalid history cursor fields")
    return cursor


def read_coin_cursor(text):
    """The cursor of a page of coins: the last coin of the page before, and the moment it was read at."""
    numbers = {"pending_revision": (0, (1 << 64) - 1), "block_height": (0, (1 << 64) - 1), "output_index": (0, (1 << 32) - 1)}
    cursor = cursor_of(text, "UTXO", numbers, ("transaction_id", "tip_hash"))
    if cursor["block_height"] > NO_BLOCK or not is_id(cursor["transaction_id"]) or not is_id(cursor["tip_hash"]):
        raise Answer(400, "invalid UTXO cursor fields")
    return cursor


class Picture:
    """The chain and the waiting transactions at one moment; every answer about addresses is read from one of these."""

    def __init__(self, chain, height, tip_hash, revision, waiting, first_seen, now):
        self.chain = chain
        self.height = height
        self.tip_hash = tip_hash
        self.revision = revision
        self.now = now
        self.w_where = {}           # txid -> when it was first seen waiting
        self.w_outputs = {}         # (txid, n) -> (amount, script)
        self.w_spends = {}          # (txid, n) -> the waiting transaction that spends it
        self.w_inputs = {}
        self.w_count = {}
        self.w_by_script = {}
        for tx in waiting:
            txid = tx["txid"]
            self.w_where[txid] = first_seen.get(txid, now)
            self.w_inputs[txid] = tx["inputs"]
            for spent in tx["inputs"]:
                self.w_spends[spent] = txid
            self.w_count[txid] = len(tx["outputs"])
            for n, (amount, script) in enumerate(tx["outputs"]):
                self.w_outputs[(txid, n)] = (amount, script)
                self.w_by_script.setdefault(script, []).append((txid, n))

    def status(self, ready=True):
        return {
            "ready": ready, "indexed_height": self.height, "indexed_hash": self.tip_hash, "observed_node_height": self.height,
            "observed_node_hash": self.tip_hash, "last_node_contact": self.now, "pending_available": ready,
            "pending_observed_at": self.now, "pending_revision": self.revision, "max_age_seconds": MAX_AGE_SECONDS,
        }

    def output(self, key):
        found = self.chain.outputs.get(key)
        return (found[0], found[1]) if found else self.w_outputs.get(key)

    def coins(self, scripts, cursor, limit, hidden=()):
        """
        Outputs in blocks that nothing spends, not even a waiting transaction, a page at a time: ordered by
        height, id and number, at most [limit] of them, and a cursor when there are more. A cursor holds for
        the block and the picture of the waiting transactions it was made at, and for nothing later.
        """
        after = None
        if cursor is not None:
            after = read_coin_cursor(cursor)
            if after["tip_hash"] != self.tip_hash or after["pending_revision"] != self.revision:
                raise Answer(409, CHANGED)
        rows = []
        for _, script in scripts:
            for key in self.chain.by_script.get(script, ()):
                amount, _, height = self.chain.outputs[key]
                # left out as the node's own set of unspent outputs leaves them out: the first block, data outputs, oversized scripts
                if height == 0 or script.startswith("6a") or len(script) > 20_000:
                    continue
                if key in self.chain.spends or key in self.w_spends or key in hidden:
                    continue
                rows.append((height, key[0], key[1], amount, script))
        rows.sort()
        if after is not None:
            last = (after["block_height"], after["transaction_id"].lower(), after["output_index"])
            rows = [row for row in rows if row[:3] > last]
        page = rows[:limit]
        next_cursor = None
        if len(rows) > limit:
            height, txid, n = page[-1][:3]
            last = {"tip_hash": self.tip_hash, "pending_revision": self.revision, "block_height": height,
                    "transaction_id": txid, "output_index": n}
            next_cursor = json.dumps(last, separators=(",", ":")).encode().hex()
        return [{
            "transaction_id": txid, "output_index": n, "amount": amount, "script_pubkey": script, "block_height": height,
            "confirmations": self.height - height + 1, "coinbase": self.chain.where[txid][2],
        } for height, txid, n, amount, script in page], next_cursor

    def used(self, scripts):
        return sorted(address for address, script in scripts if script in self.chain.by_script or script in self.w_by_script)

    def known(self, txid):
        return txid in self.chain.where or txid in self.w_where

    def history(self, scripts, cursor, limit):
        """The transactions that pay to the scripts or spend from them: the waiting ones first, then by height downwards, then by id."""
        mine = {script for _, script in scripts}
        after = None
        if cursor is not None:
            after = read_cursor(cursor)
            if after["tip_hash"] != self.tip_hash or after["pending_revision"] != self.revision:
                raise Answer(409, CHANGED)
        touched = set()
        for script in mine:
            for key in list(self.chain.by_script.get(script, ())) + list(self.w_by_script.get(script, ())):
                touched.add(key[0])
                for spender in (self.chain.spends.get(key), self.w_spends.get(key)):
                    if spender is not None:
                        touched.add(spender)
        rows = []
        for txid in touched:
            if txid in self.chain.where:
                rows.append((self.chain.where[txid][0], txid, self.chain.where[txid][1]))
            if txid in self.w_where:
                rows.append((NO_BLOCK, txid, self.w_where[txid]))
        rows.sort(key=lambda row: (-row[0], row[1]))
        if after is not None:
            rows = [row for row in rows if row[0] < after["height"] or (row[0] == after["height"] and row[1] > after["txid"])]
        page = rows[:limit]
        lines = [self.line(txid, None if height == NO_BLOCK else height, stamp, mine) for height, txid, stamp in page]
        next_cursor = None
        if len(rows) > limit:
            height, txid, _ = page[-1]
            last = {"tip_hash": self.tip_hash, "pending_revision": self.revision, "height": height, "txid": txid}
            next_cursor = json.dumps(last, separators=(",", ":")).encode().hex()
        return lines, next_cursor

    def line(self, txid, height, stamp, mine):
        """
        One transaction summed up against the scripts that were asked about, the way the real server does it:
        what its outputs pay to them less what its inputs take from them, and the fee of the whole transaction,
        whoever paid it. A block reward has no fee.
        """
        in_block = txid in self.chain.where
        count = self.chain.count[txid] if in_block else self.w_count[txid]
        inputs = self.chain.inputs[txid] if in_block else self.w_inputs[txid]
        received = paid_out = paid_in = own_in = 0
        for n in range(count):
            amount, script = self.output((txid, n))
            paid_out += amount
            if script in mine:
                received += amount
        for key in inputs:
            spent = self.output(key)
            if spent is None:
                raise RuntimeError("an input of %s is not among the outputs read" % txid)
            paid_in += spent[0]
            if spent[1] in mine:
                own_in += spent[0]
        reward = in_block and self.chain.where[txid][2]
        return {
            "transaction_id": txid, "net_amount": received - own_in, "network_fee": None if reward else paid_in - paid_out,
            "block_height": height, "confirmations": 0 if height is None else self.height - height + 1, "timestamp": stamp,
        }


class Index:
    """Reads the node and remembers the blocks it has read, so that a look costs only what is new."""

    def __init__(self, node):
        self.node = node
        self.blocks = {}            # block hash -> the block, reduced
        self.chain = Chain()
        self.pending = {}           # txid -> a waiting transaction read earlier
        self.first_seen = {}        # txid -> when it was first seen waiting
        self.moment = None          # the tip, the node's count of changes to its waiting transactions, and their ids
        self.revision = 0           # goes up whenever the moment changes; a history cursor holds for one revision

    def block(self, block_hash):
        block = self.blocks.get(block_hash)
        if block is None:
            raw = self.node.call("getblock", block_hash, 2)
            block = {"hash": raw["hash"], "prev": raw.get("previousblockhash"), "time": raw["time"], "txs": [parsed(t) for t in raw["tx"]]}
            self.blocks[block_hash] = block
        return block

    def follow(self, tip_hash):
        """Brings the chain up to the node's tip. When another branch has won, the common part is kept and the rest read again."""
        if self.chain.hashes and self.chain.hashes[-1] == tip_hash:
            return
        known = {block_hash: height for height, block_hash in enumerate(self.chain.hashes)}
        fresh = []
        cursor = tip_hash
        while cursor is not None and cursor not in known:
            block = self.block(cursor)
            fresh.append(block)
            cursor = block["prev"]
        if cursor is None or known[cursor] != len(self.chain.hashes) - 1:
            keep = [] if cursor is None else [self.blocks[h] for h in self.chain.hashes[:known[cursor] + 1]]
            self.chain = Chain()
            for block in keep:
                self.chain.add(block)
        for block in reversed(fresh):
            self.chain.add(block)

    def look(self):
        """One picture of the node in which the tip and the waiting transactions belong together."""
        for _ in range(5):
            info = self.node.call("getblockchaininfo")
            waiting = self.node.call("getrawmempool", False, True)
            try:
                self.follow(info["bestblockhash"])
                txs = []
                for txid in waiting["txids"]:
                    if txid not in self.pending:
                        self.pending[txid] = parsed(self.node.call("getrawtransaction", txid, True))
                    txs.append(self.pending[txid])
            except Refused:
                continue                                      # a transaction left the waiting ones while it was being read
            after = self.node.call("getblockchaininfo")
            if after["bestblockhash"] != info["bestblockhash"] or self.node.call("getrawmempool", False, True) != waiting:
                continue
            moment = (info["bestblockhash"], waiting["mempool_sequence"], tuple(sorted(waiting["txids"])))
            if moment != self.moment:
                self.moment = moment
                self.revision += 1
            now = int(time.time())
            for txid in waiting["txids"]:
                self.first_seen.setdefault(txid, now)
            return Picture(self.chain, info["blocks"], info["bestblockhash"], self.revision, txs, self.first_seen, now)
        raise Answer(503, "index is synchronizing or stale; retry shortly")


BAD_JSON = "Request must contain valid JSON with the required fields."
STALE = "index is synchronizing or stale; retry shortly"
GETS = ("/ready", "/v1/chain/tip", "/v1/fees/estimate", "/v2/fees/estimate")


class App:
    def __init__(self, node):
        self.node = node
        self.index = Index(node)
        self.lock = threading.Lock()        # one request at a time: this is a test double, not a server
        self.network = None                 # "main" or "regtest", as the node names it
        self.stale = False
        self.busy = 0
        self.hold = False
        self.refuse = ""                    # a reason: every transaction is refused with it, and kept
        self.hide = False
        self.page = 0                       # coins on a page of /v1/utxos when the request names no limit; 0: as the real server
        self.kept = []                      # what a server that said no was shown, as hexadecimal text
        self.kept_coins = set()             # (txid, n) of every coin those transactions spend
        self.posts = {
            "/v1/addresses/activity": self.activity, "/v1/utxos": self.utxos, "/v1/transactions": self.transactions,
            "/v1/transactions/validate": self.validate, "/v1/transactions/broadcast": self.broadcast,
            "/v1/transactions/status": self.status,
        }

    def handle(self, method, path, content_type, raw):
        with self.lock:
            if path == "/__standin/set" and method == "POST":
                wanted = json.loads(raw.decode())
                self.stale = bool(wanted.get("stale", self.stale))
                self.busy = int(wanted.get("busy", self.busy))
                self.hold = bool(wanted.get("hold", self.hold))
                self.refuse = str(wanted.get("refuse", self.refuse) or "")
                self.hide = bool(wanted.get("hide", self.hide))
                self.page = int(wanted.get("page", self.page))
                return 200, {"stale": self.stale, "busy": self.busy, "hold": self.hold, "refuse": self.refuse,
                             "hide": self.hide, "page": self.page, "kept": self.kept}
            if path.startswith(("/v1/", "/v2/")) and self.busy > 0:
                # the real server counts every request against its limit, valid or not, and leaves /ready out
                self.busy -= 1
                raise Answer(429, "The server request rate limit was reached; retry in one second.", {"Retry-After": "1"})
            if method == "GET" and path == "/ready":
                return (503 if self.stale else 200), self.index.look().status(not self.stale)
            if method == "GET" and path == "/v1/chain/tip":
                return 200, {"height": self.node.call("getblockchaininfo")["blocks"]}
            if method == "GET" and path == "/v1/fees/estimate":
                return 200, {"atomic_units_per_vbyte": -(-self.fee_rate() // 1000), "target_blocks": 6}
            if method == "GET" and path == "/v2/fees/estimate":
                return 200, {"atomic_units_per_kvbyte": self.fee_rate(), "target_blocks": 6}
            if method == "POST" and path in self.posts:
                if (content_type or "").split(";")[0].strip().lower() != "application/json":
                    raise Answer(415, "Request must use application/json.")
                try:
                    body = json.loads(raw.decode())
                except Exception:
                    raise Answer(400, BAD_JSON)
                if not isinstance(body, dict):
                    raise Answer(422, BAD_JSON)
                return 200, self.posts[path](body)
            raise Answer(405 if path in self.posts or path in GETS else 404, "Request could not be served.")

    def fee_rate(self):
        """Base units for 1,000 virtual bytes: the highest of the node's estimate for six blocks and its two minimums."""
        rates = [units(self.node.call("getmempoolinfo")["mempoolminfee"]), units(self.node.call("getnetworkinfo")["relayfee"])]
        estimate = self.node.call("estimatesmartfee", 6)
        if estimate.get("feerate") is not None:
            rates.append(units(estimate["feerate"]))
        if max(rates) == 0:
            raise NodeDown("the node gave a fee rate of zero")
        return max(rates)

    def scripts(self, body):
        """The addresses of a request as (address, script), each script once. The node itself says what an address is."""
        addresses = body.get("addresses")
        if not isinstance(addresses, list) or not all(isinstance(a, str) for a in addresses):
            raise Answer(422, BAD_JSON)
        if len(addresses) > MAX_ADDRESSES:
            raise Answer(400, "wallet data requests accept at most 100 addresses")
        found, seen = [], set()
        for address in addresses:
            if len(address) > 128:
                raise Answer(400, "address is too long")
            checked = self.node.call("validateaddress", address)
            if not checked.get("isvalid"):
                # the real server tells a good address of another network from something that is no address
                if self.network is None:
                    self.network = self.node.call("getblockchaininfo")["chain"]
                foreign = bech32_checksum_holds(address) and address.lower().rpartition("1")[0] != HRPS.get(self.network)
                raise Answer(400, "address belongs to a different network" if foreign else "invalid address or checksum")
            script = checked["scriptPubKey"]
            if script not in seen:
                seen.add(script)
                found.append((checked.get("address", address), script))
        return found

    def picture(self):
        if self.stale:
            raise Answer(503, STALE)
        return self.index.look()

    def activity(self, body):
        scripts = self.scripts(body)
        picture = self.picture()
        return {"used_addresses": picture.used(scripts), "index": picture.status()}

    def paging(self, body, usual):
        """The cursor and the limit of a request as the real server reads them: a text or nothing, and a whole number of sixteen bits or nothing."""
        cursor, limit = body.get("cursor"), body.get("limit")
        if cursor is not None and not isinstance(cursor, str):
            raise Answer(422, BAD_JSON)
        if limit is None:
            limit = usual
        if not whole(limit, 0, 65535):
            raise Answer(422, BAD_JSON)
        return cursor, limit

    def utxos(self, body):
        cursor, limit = self.paging(body, self.page or UTXO_PAGE)
        scripts = self.scripts(body)
        picture = self.picture()
        if not 1 <= limit <= MAX_UTXO_PAGE:
            raise Answer(400, "UTXO limit must be between 1 and 10,000")
        coins, next_cursor = picture.coins(scripts, cursor, limit, self.kept_coins if self.hide else ())
        return {"utxos": coins, "next_cursor": next_cursor, "index": picture.status()}

    def transactions(self, body):
        cursor, limit = self.paging(body, 100)
        scripts = self.scripts(body)
        picture = self.picture()
        if not 1 <= limit <= 200:
            raise Answer(400, "history limit must be between 1 and 200")
        lines, next_cursor = picture.history(scripts, cursor, limit)
        return {"transactions": lines, "next_cursor": next_cursor, "index": picture.status()}

    def signed(self, body):
        text = body.get("transaction_hex")
        if not isinstance(text, str):
            raise Answer(422, BAD_JSON)
        if not text or len(text) > MAX_HEX or len(text) % 2 or any(c not in "0123456789abcdefABCDEF" for c in text):
            raise Answer(400, "invalid RPC data: signed transaction must be valid hexadecimal data of at most 800,000 characters")
        return text

    def keep(self, text):
        """Remembers a transaction that the server refused, and the coins it spends. (The test switch "refuse".)"""
        if text not in self.kept:
            self.kept.append(text)
            for spent in self.node.call("decoderawtransaction", text)["vin"]:
                self.kept_coins.add((spent["txid"], spent["vout"]))

    def validate(self, body):
        text = self.signed(body)
        if self.refuse:
            self.keep(text)
            return {"allowed": False, "rejection_reason": self.refuse}
        verdict = self.node.call("testmempoolaccept", [text])
        if not isinstance(verdict, list) or len(verdict) != 1:
            raise NodeDown("testmempoolaccept answered in an unknown form")
        return {"allowed": bool(verdict[0]["allowed"]), "rejection_reason": verdict[0].get("reject-reason")}

    def broadcast(self, body):
        text = self.signed(body)
        if self.refuse:
            self.keep(text)
            raise NodeDown("refused by the test")
        if self.hold:
            raise NodeDown("held back by the test")
        self.node.call("getblockchaininfo")
        return {"transaction_id": self.node.call("sendrawtransaction", text)}

    def status(self, body):
        txid = body.get("transaction_id")
        if not isinstance(txid, str):
            raise Answer(422, BAD_JSON)
        if not is_id(txid):
            raise Answer(400, "transaction ID must be 64 hexadecimal characters")
        txid = txid.lower()
        if self.picture().known(txid):
            return {"known": True}
        try:
            self.node.call("getrawtransaction", txid, False)
            return {"known": True}
        except Refused as e:
            if e.code == -5:
                return {"known": False}
            raise


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        if self.server.verbose:
            sys.stderr.write("%s %s\n" % (time.strftime("%H:%M:%S"), fmt % args))

    def reply(self, status, body, headers=None):
        data = json.dumps(body, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(data)))
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        self.end_headers()
        self.wfile.write(data)

    def serve(self, method):
        try:
            raw = b""
            if method == "POST":
                length = int(self.headers.get("Content-Length") or 0)
                if length > MAX_BODY:
                    self.close_connection = True              # the body is not read
                    raise Answer(413, "Request exceeds the 1 MB body limit.")
                raw = self.rfile.read(length)
            status, answer = self.server.app.handle(method, self.path, self.headers.get("Content-Type"), raw)
            self.reply(status, answer)
        except Answer as answer:
            self.reply(answer.status, {"message": answer.message}, answer.headers)
        except (NodeDown, Refused) as e:
            if self.server.verbose:
                sys.stderr.write("node: %s\n" % e)
            self.reply(502, {"message": NODE_ERROR})
        except Exception as e:                                # a fault of this program: say so loudly, answer like the real server
            sys.stderr.write("stand-in fault: %r\n" % (e,))
            self.reply(500, {"message": "Could not read wallet data."})

    def do_GET(self):
        self.serve("GET")

    def do_POST(self):
        self.serve("POST")


def main():
    parser = argparse.ArgumentParser(description="A stand-in for a Northbound wallet server, for tests.")
    parser.add_argument("--rpc-url", required=True, help="the node's RPC address, for example http://127.0.0.1:18443")
    parser.add_argument("--cookie", required=True, help="the node's cookie file (DATADIR/regtest/.cookie)")
    parser.add_argument("--port", type=int, default=0, help="0 takes a free port")
    parser.add_argument("--warm", action="store_true", help="read the whole chain before listening")
    parser.add_argument("--exit-with-stdin", action="store_true", help="stop when standard input closes, so that it never outlives its test")
    parser.add_argument("--verbose", action="store_true")
    args = parser.parse_args()
    app = App(Node(args.rpc_url, args.cookie))
    if args.warm:
        app.index.look()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    server.daemon_threads = True
    server.app = app
    server.verbose = args.verbose
    if args.exit_with_stdin:
        def leave():
            sys.stdin.buffer.read()
            server.shutdown()
        threading.Thread(target=leave, daemon=True).start()
    print("listening %d" % server.server_address[1], flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
