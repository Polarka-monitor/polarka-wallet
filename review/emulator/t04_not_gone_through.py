#!/usr/bin/env python3
"""A payment that did not go through cannot arrive beside the next one: on an emulator, against the test bench.

  t04_not_gone_through.py SERIAL APK

The wallet is set up with a node of its own behind a gate (tls_gate.py) that can keep a transaction to itself.
A payment is kept from the node; while its outcome is not known, no other payment is worked out. A minute later
the app says that it did not go through and keeps it on the overview. The next payment is built on its coin although a larger coin has arrived meanwhile, and the node then
has no room for the kept transaction: not beside the new payment, and not after its block.

The emulator must be running with a screen lock and a fingerprint and must trust the test authority, as for
t02_wallet.py. The app is installed fresh.
"""
import json
import os
import re
import subprocess
import sys
import time

from emu import Phone
import flows
from flows import bench, bench_state, tab, top, refresh, scroll_to, number_after

PKG = "org.polarka.wallet"
HERE = os.path.dirname(os.path.abspath(__file__))
serial, apk = sys.argv[1], sys.argv[2]
p = Phone(serial)
st = bench_state()
done = []


def step(text):
    done.append(text)
    print("ok    %2d  %s" % (len(done), text), flush=True)


def expect(condition, what):
    if not condition:
        p.show("at the failure")
        raise SystemExit("FAILED after step %d: %s" % (len(done), what))


def amount(label, index=0):
    return number_after(p, label, index)


def fresh_look(seconds=4):
    tab(p, "Overview")
    top(p)
    refresh(p, seconds)
    p.wait("Asking the node", 30, gone=True)


def fill_payment(to, coins):
    tab(p, "Send")
    top(p)
    p.tap("Recipient address")
    p.type(to)
    p.tap("Amount (NORTH)")
    p.type(coins)
    p.back()
    time.sleep(0.6)
    scroll_to(p, "Check the payment")
    p.tap("Check the payment")
    p.wait("Confirm the payment", 30)


def sign():
    scroll_to(p, "Sign with my fingerprint and send")
    p.tap("Sign with my fingerprint and send")
    flows.fingerprint(p)


def node_takes(hex_text):
    """Hands a transaction to the node as somebody else would. True if the node takes it."""
    r = subprocess.run([sys.executable, os.path.join(HERE, "bench.py"), "call", "sendrawtransaction", hex_text],
                       capture_output=True, text=True, timeout=120)
    return r.returncode == 0


number = os.environ.get("BENCH", "")
gate_port = 18653 + (10 * int(number) if number else 0)
folder = os.path.join(st["work"], "gate")
os.makedirs(folder, exist_ok=True)
keep, kept = os.path.join(folder, "keep"), os.path.join(folder, "kept.txt")
for name in (keep, kept):
    if os.path.exists(name):
        os.remove(name)
gate = subprocess.Popen(
    [sys.executable, os.path.join(HERE, "tls_gate.py"), str(gate_port), str(st["node_port"]),
     os.path.join(HERE, "tls", "server.pem"), os.path.join(HERE, "tls", "server.key"), folder],
    stdout=open(os.path.join(folder, "gate.log"), "w"), stderr=subprocess.STDOUT)
try:
    time.sleep(1.5)
    with open(os.path.join(st["work"], "phone-password")) as f:
        password = f.read().strip()
    path = os.path.join(st["work"], "setup-gate.json")
    with open(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as f:
        # The watched address is a fresh one with no history. The gate makes a new TLS connection for every
        # request, and watching the bench's own address would have the app read every block of the chain through it.
        json.dump({"format": "north-watch-setup/1", "node": {"url": "https://10.0.2.2:%d" % gate_port, "user": "phone", "password": password},
                   "chain": "regtest", "addresses": [bench("call", "wallet=bench", "getnewaddress")]}, f)

    print("Android %s, %s, a node of one's own behind a gate" % (p.release(), os.path.basename(apk)), flush=True)
    p.adb("uninstall", PKG)
    out = p.adb("install", apk, timeout=180)
    expect("Success" in out, "the package was not installed: %s" % out[-200:])
    p.start(PKG + "/.MainActivity")
    p.wait("Connect to a public node", 30)
    flows.import_setup(p, path)
    p.tap("Save and connect")
    p.wait("Total", 40, exact=True)
    if p.has("The setup file was deleted"):
        p.tap("OK", exact=True)
    flows.make_wallet(p)
    address_1 = flows.receive_address(p)
    bench("pay", address_1, 10)
    bench("mine", 1)
    fresh_look()
    expect(amount("Can be spent") == "10.00000000", "10 NORTH did not arrive: " + amount("Can be spent"))
    step("a wallet with one coin of 10 NORTH, at a node behind a gate that can keep a transaction to itself")

    open(keep, "w").close()
    fill_payment(st["bench_address"], "5")
    sign()
    p.wait("It is not known whether the payment went out", 90)
    os.remove(keep)
    with open(kept) as f:
        lost = f.read().split()[-1]
    lost_id = json.loads(bench("call", "decoderawtransaction", lost))["txid"]
    expect(lost_id not in bench("call", "getrawmempool"), "the node got the transaction that was to be kept from it")
    # the app is closed and opened again: what it knows about the payment is in its file, and the form is empty again
    p.stop(PKG)
    p.start(PKG + "/.MainActivity")
    flows.unlock_if_locked(p)
    p.wait("Total", 40, exact=True)
    p.wait("Handed to the node, outcome not known", 20)
    expect(p.has(lost_id), "the payment whose outcome is not known is not shown with its id")
    expect(amount("Can be spent") == "0.00000000" and amount("In payments waiting for a block") == "10.00000000",
           "the coin of that payment is shown as one that can be spent")
    step("a payment of 5 NORTH was kept from the node: the app says that the outcome is not known and sets the coin aside")

    tab(p, "Send")
    top(p)
    p.tap("Recipient address")
    p.type(st["bench_address"])
    p.tap("Amount (NORTH)")
    p.type("2")
    p.back()
    time.sleep(0.6)
    scroll_to(p, "Check the payment")
    p.tap("Check the payment")
    p.wait("It is not yet known whether your last payment went out", 20)
    expect(not p.has("Confirm the payment"), "a payment was worked out while the outcome of another is not known")
    p.stop(PKG)                         # the form is empty again after that
    p.start(PKG + "/.MainActivity")
    flows.unlock_if_locked(p)
    p.wait("Total", 40, exact=True)
    step("while that is open, no other payment is worked out")

    address_2 = flows.receive_address(p)
    bench("pay", address_2, 50)
    bench("mine", 1)
    time.sleep(62)                      # a payment is not called gone before it is a minute old
    fresh_look(8)
    p.wait("is no longer held by the node", 60)
    p.tap("OK", exact=True)
    expect(p.has("Did not go through, could still arrive") and p.has(lost_id), "the payment is not kept on the overview")
    expect(amount("Can be spent") == "60.00000000", "its coin is not free again: " + amount("Can be spent"))
    step("a minute later: 'it did not go through', the payment stays on the overview, its coin is free, a coin of 50 has arrived")

    fill_payment(st["bench_address"], "5")
    expect(p.has("spends a coin of an earlier payment that did not go through"), "the confirmation does not say that this payment stands against the earlier one")
    expect(amount("Coins used") == "1", "coins used: " + amount("Coins used"))
    sign()
    p.wait("The node accepted the transaction", 40)
    new_id = [t for t in p.texts() if re.match(r"^[0-9a-f]{64}$", t)][0]
    p.tap("OK", exact=True)
    expect(new_id != lost_id and new_id in bench("call", "getrawmempool"), "the node does not hold the new payment")
    spent = json.loads(bench("call", "decoderawtransaction", lost))["vin"]
    again = json.loads(bench("call", "getrawtransaction", new_id, "1"))["vin"]
    expect([(i["txid"], i["vout"]) for i in again] == [(i["txid"], i["vout"]) for i in spent], "the new payment does not spend the coin of the old one")
    step("the next payment of 5 NORTH spends the coin of the old one, not the larger coin, and the screen says why")

    expect(not node_takes(lost), "the node took the kept transaction beside the new payment")
    bench("mine", 1)
    expect(not node_takes(lost), "the node took the kept transaction after the new payment was in a block")
    fresh_look()
    expect(not p.has("Did not go through, could still arrive"), "the entry stays although a block has settled it")
    expect(amount("Can be spent") == "54.99999674", "after the block: " + amount("Can be spent"))
    tab(p, "History")
    paid = [t for t in p.texts() if t == "-5.00000326"]
    expect(len(paid) == 1, "the history shows %d payments of 5 NORTH, expected one" % len(paid))
    step("the kept transaction is refused by the node beside the new payment and after its block; one payment arrived")
    print("NOT GONE THROUGH: ALL %d STEPS PASSED" % len(done), flush=True)
finally:
    gate.terminate()
