#!/usr/bin/env python3
"""The package as installed looks at one watched address through the public wallet server.

  t09_real_server_watch.py SERIAL APK ADDRESS_FILE

This run touches a real network and is not part of matrix.sh: start it on purpose, and not often. The app is
installed fresh and is given a setup file that names the public Northbound server and one address to watch.
The address is taken from the first line of ADDRESS_FILE and is not printed. It has to be public chain data that
is nobody's secret, and it tells most when it holds more coins than one page takes. There is no wallet in the
phone and nothing is sent: the server is only read.

The app has to show what the address holds. That is compared with what the same server lists for the address
when this machine asks it, page by page, right before and right after the app's look. An address in use can
change with any block, so the comparison is repeated a few times until the two readings around a look agree.
The server's picture also changes with every transaction that begins to wait anywhere on the network: a
reading of this machine that such a change cuts (the server answers 409, or 503 while it takes the new
picture) counts as one that did not agree, and is made again with the next look. Then the setup is removed
again.

It shows that the package under test reads the real server's coins, also when they come in several pages, and
its history. It shows nothing about payments through that server.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

from emu import Phone
import flows
from flows import top

PKG = "org.polarka.wallet"
SERVER = "https://api.northboundwallet.org"
serial, apk, address_file = sys.argv[1], sys.argv[2], sys.argv[3]
address = open(address_file).read().split()[0]
p = Phone(serial)
done = []


def step(text):
    done.append(text)
    print("ok    %2d  %s" % (len(done), text), flush=True)


def expect(condition, what):
    if not condition:
        print("at the failure the screen shows: %s" % [t.replace(address, "(the address)") for t in p.texts()[:60]], flush=True)
        raise SystemExit("FAILED after step %d: %s" % (len(done), what))


def listed():
    """What the server lists for the address: (block, number of the waiting transactions, coins, their sum, pages)."""
    coins, total, pages, cursor, at = 0, 0, 0, None, None
    while True:
        body = {"addresses": [address]}
        if cursor is not None:
            body["cursor"] = cursor
        request = urllib.request.Request(SERVER + "/v1/utxos", json.dumps(body).encode(), {"Content-Type": "application/json"})
        try:
            with urllib.request.urlopen(request, timeout=60) as answer:
                page = json.loads(answer.read().decode())
        except urllib.error.HTTPError as refused:
            # 409: the server took a new picture between two pages and no longer knows the cursor. 503: it is taking
            # one right now. Either way this reading is over, as it is for the app: read again.
            if refused.code in (409, 503):
                return None
            raise
        now = (page["index"]["indexed_height"], page["index"].get("pending_revision"))
        if at is not None and now != at:
            return None                                   # the picture changed between two pages: read again
        at = now
        pages += 1
        coins += len(page["utxos"])
        total += sum(int(u["amount"]) for u in page["utxos"])
        cursor = page.get("next_cursor")
        if cursor is None:
            return at[0], at[1], coins, total, pages


def written(sat):
    return "%d.%08d" % (sat // 100_000_000, sat % 100_000_000)


def shown_total():
    """The amount beside "Total" in the block of the watched addresses."""
    flows.seek(p, "Watched addresses")
    nodes = p.nodes()
    rows = [n for n in nodes if n.label == "Total"]
    if not rows:
        return None
    row = rows[-1]
    beside = [n for n in nodes if n.label and n is not row and abs(n.center[1] - row.center[1]) < 30 and n.box[0] > row.box[0]]
    return "".join(c for c in beside[0].label if c.isdigit() or c == ".") if beside else None


print("Android %s, %s, the public server and one watched address" % (p.release(), os.path.basename(apk)), flush=True)
p.adb("uninstall", PKG)
out = p.adb("install", apk, timeout=180)
expect("Success" in out, "the package was not installed: %s" % out[-200:])
path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "run-real-setup.json")
with open(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as f:
    json.dump({"format": "polarka-setup/3", "server": {"url": SERVER}, "chain": "main", "addresses": [address]}, f)
p.start(PKG + "/.MainActivity")
flows.first_screen(p)
flows.import_setup(p, path)
os.remove(path)
expect(p.has("Server", exact=True) and p.has(SERVER, exact=True) and p.has("main", exact=True),
       "the check before saving does not show the public server on the main network")
expect(p.has("Addresses to watch"), "the check before saving does not show that an address will be watched")
p.tap("Save and connect")
p.wait("Total", 90, exact=True)
flows.asking_gone(p, 180)
expect(not p.has("cannot be reached") and not p.has("answered with an error"), "the app reports a problem with the server")
flows.seek(p, "The server answers, block")
step("a setup file that names %s and one address to watch was saved: the server answers, and the app has made its first look" % SERVER)

agreed = None
for attempt in range(1, 7):
    before = listed()
    flows.tab(p, "Overview")
    top(p)
    flows.refresh(p, 3)
    flows.asking_gone(p, 180)
    shown = shown_total()
    after = listed()
    print("note      look %d: the app shows %s; the server listed %s before and %s after" % (
        attempt, shown, before and (before[0], before[2], written(before[3])), after and (after[0], after[2], written(after[3]))), flush=True)
    if before is not None and before == after and shown is not None:
        agreed = (shown, before)
        break
    time.sleep(20)
expect(agreed is not None, "in six looks the server's list never stood still around a look of the app")
shown, (height, revision, coins, total, pages) = agreed
expect(not p.has("answered with an error") and not p.has("The server is busy"), "the app reports a problem with the server")
expect(shown == written(total), "the app shows %s where the server lists %s" % (shown, written(total)))
step("the app shows %s NORTH for the watched address: the sum of the %d coins that the server lists for it at block %d, which this machine read over %d page%s" % (
    shown, coins, height, pages, "" if pages == 1 else "s"))

flows.tab(p, "History")
time.sleep(2)
kinds = [k for k in ("block reward", "received", "paid") if p.has(k)]
expect(kinds and p.has("watched address"), "the history shows no movement of the watched address")
expect(not p.has("answered with an error"), "the history page reports a problem with the server")
step("the history lists movements of the watched address (on the screen: %s)" % ", ".join(kinds))

flows.tab(p, "Overview")
flows.remove_setup(p, wallet=False)
p.adb("uninstall", PKG)
p.sh("rm -f /sdcard/Download/polarka-setup.json")
step("the setup was removed again and the app taken off; there was no wallet in the phone and nothing was sent")
print("REAL SERVER, A WATCHED ADDRESS: ALL %d STEPS PASSED on Android %s" % (len(done), p.release()), flush=True)
