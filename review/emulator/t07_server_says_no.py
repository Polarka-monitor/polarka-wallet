#!/usr/bin/env python3
"""A wallet server that says no cannot make the wallet pay twice: on an emulator, against the test bench.

  t07_server_says_no.py SERIAL APK

The wallet is set up with a wallet server (the stand-in of review/standin behind TLS). The stand-in has two
switches that play a server which is not honest. With the first it answers that the node does not take a
transaction, asks the node nothing, and keeps what it was shown. With the second it leaves the coins of what it
kept out of its list.

A server that was shown a signed payment has it, whatever it answers. So the app does not forget a payment that
a server refused: it keeps it on the overview as one that did not go through, and builds the next payment on one
of its coins. The run then hands the kept transaction to the node, as that server could: the node has no room
for it, not beside the new payment and not after its block. When the server hides the coin of a kept payment,
the entry stays and no payment is worked out; nothing is concluded from a coin that a server does not list.

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
WHY = "min relay fee not met"
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
    flows.asking_gone(p)


def type_payment(to, coins):
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


def fill_payment(to, coins):
    type_payment(to, coins)
    p.wait("Confirm the payment", 30)


def sign():
    scroll_to(p, "Sign with my fingerprint and send")
    p.tap("Sign with my fingerprint and send")
    flows.fingerprint(p)


def reopen():
    """The app is closed and opened again: what it knows is in its file, and the Send form is empty."""
    p.stop(PKG)
    p.start(PKG + "/.MainActivity")
    flows.unlock_if_locked(p)
    p.wait("Total", 40, exact=True)


def switches(text):
    return json.loads(bench("standin", text))


def decoded(hex_text):
    return json.loads(bench("call", "decoderawtransaction", hex_text))


def coins_of(tx):
    return [(i["txid"], i["vout"]) for i in tx["vin"]]


def node_takes(hex_text):
    """Hands a transaction to the node as somebody else would. True if the node takes it."""
    r = subprocess.run([sys.executable, "-B", os.path.join(HERE, "bench.py"), "call", "sendrawtransaction", hex_text],
                       capture_output=True, text=True, timeout=120)
    return r.returncode == 0


def refused_payment(coins, already):
    """Pays while the server says no. Returns the transaction the server kept, as hexadecimal text."""
    switches(json.dumps({"refuse": WHY}))
    try:
        fill_payment(st["bench_address"], coins)
        sign()
        p.wait("The server says that the node behind it does not take the transaction (%s)" % WHY, 90)
        expect(p.has("the app keeps the payment on the Overview page as one that did not go through"),
               "the app does not say that it keeps a payment which a server refused")
        expect(not p.has("Nothing was sent"), "the app says that nothing was sent, which it cannot know of a server")
    finally:
        kept = switches('{"refuse": ""}')["kept"]
    expect(len(kept) == already + 1, "the server was to keep %d transactions and kept %d" % (already + 1, len(kept)))
    return kept[-1]


def gone_after_the_block():
    """After a block the entry is gone. Returns True if the app said that the kept payment cannot arrive any more."""
    fresh_look()
    said = p.has("cannot arrive any more")
    if said:
        p.tap("OK", exact=True)
        time.sleep(0.6)
    expect(not p.has("Did not go through, could still arrive"), "the entry stays although a block has settled it")
    return said


already = len(switches('{"stale": false, "busy": 0, "hold": false, "refuse": "", "hide": false}')["kept"])
try:
    print("Android %s, %s, a wallet server that says no" % (p.release(), os.path.basename(apk)), flush=True)
    p.adb("uninstall", PKG)
    out = p.adb("install", apk, timeout=180)
    expect("Success" in out, "the package was not installed: %s" % out[-200:])
    p.start(PKG + "/.MainActivity")
    flows.first_screen(p)
    flows.import_setup(p, bench("setup", "server"))
    p.tap("Save and connect")
    p.wait("Total", 40, exact=True)
    flows.make_wallet(p)
    address_1 = flows.receive_address(p)
    bench("pay", address_1, 10)
    bench("mine", 1)
    fresh_look()
    expect(amount("Can be spent") == "10.00000000", "10 NORTH did not arrive: " + amount("Can be spent"))
    step("a wallet with one coin of 10 NORTH, at a wallet server that can say no and keep what it was shown")

    lost = refused_payment("5", already)
    lost_tx = decoded(lost)
    lost_id = lost_tx["txid"]
    expect(lost_id not in bench("call", "getrawmempool"), "the node got a transaction that the server was to keep")
    reopen()
    p.wait("Did not go through, could still arrive", 20)
    expect(p.has(lost_id), "the payment that the server refused is not kept on the overview with its id")
    fresh_look()
    expect(p.has("Did not go through, could still arrive") and p.has(lost_id), "after a look the refused payment is no longer kept")
    expect(amount("Can be spent") == "10.00000000", "its coin is not free for the next payment: " + amount("Can be spent"))
    step("the server said no to a payment of 5 NORTH and kept it: the app does not forget the payment, it keeps it on the overview as one that did not go through")

    address_2 = flows.receive_address(p)
    bench("pay", address_2, 50)
    bench("mine", 1)
    fresh_look()
    expect(amount("Can be spent") == "60.00000000", "a coin of 50 did not arrive: " + amount("Can be spent"))
    fill_payment(st["bench_address"], "5")
    expect(p.has("spends a coin of an earlier payment that did not go through"), "the confirmation does not say that this payment stands against the earlier one")
    expect(amount("Coins used") == "1", "coins used: " + amount("Coins used"))
    sign()
    flows.wait_any(p, ("The server took the transaction",), 60)
    new_id = [t for t in p.texts() if re.match(r"^[0-9a-f]{64}$", t)][0]
    p.tap("OK", exact=True)
    expect(new_id != lost_id and new_id in bench("call", "getrawmempool"), "the node does not hold the new payment")
    again = json.loads(bench("call", "getrawtransaction", new_id, "1"))
    expect(coins_of(again) == coins_of(lost_tx), "the new payment does not spend the coin of the kept one")
    step("the next payment of 5 NORTH spends the coin of the kept one, not the larger coin that has arrived, and the screen says why")

    expect(not node_takes(lost), "the node took the kept transaction beside the new payment")
    bench("mine", 1)
    expect(not node_takes(lost), "the node took the kept transaction after the new payment was in a block")
    said = gone_after_the_block()
    expect(amount("Can be spent") == "54.99999674", "after the block: " + amount("Can be spent"))
    tab(p, "History")
    paid = [t for t in p.texts() if t == "-5.00000326"]
    expect(len(paid) == 1, "the history shows %d payments of 5 NORTH, expected one" % len(paid))
    step("the kept transaction, handed to the node as the server could, is refused beside the new payment and after its block; one payment arrived%s"
         % (", and the app says that the other cannot arrive any more" if said else ""))

    lost2 = refused_payment("3", already + 1)
    lost2_tx = decoded(lost2)
    lost2_id = lost2_tx["txid"]
    expect(lost2_id not in bench("call", "getrawmempool"), "the node got the second transaction that the server was to keep")
    switches('{"hide": true}')
    reopen()
    fresh_look()
    expect(p.has("Did not go through, could still arrive") and p.has(lost2_id),
           "a payment that did not go through is let go because the server no longer lists its coin")
    flows.seek(p, "The server lists none of the coins of this payment")
    type_payment(st["bench_address"], "2")
    p.wait("An earlier payment did not go through but could still arrive", 30)
    expect(p.has("That cannot be done as things stand") and not p.has("Confirm the payment"),
           "a payment was worked out beside one that did not go through and whose coin the server hides")
    step("the server said no to a payment of 3 NORTH, kept it and hides its coin: the entry stays, the app says that the server lists none of its coins, and no payment is worked out")

    switches('{"hide": false}')
    reopen()
    fresh_look()
    fill_payment(st["bench_address"], "2")
    expect(p.has("spends a coin of an earlier payment that did not go through"), "with the coin back, the confirmation does not say that this payment stands against the earlier one")
    sign()
    flows.wait_any(p, ("The server took the transaction",), 60)
    new2_id = [t for t in p.texts() if re.match(r"^[0-9a-f]{64}$", t)][0]
    p.tap("OK", exact=True)
    again2 = json.loads(bench("call", "getrawtransaction", new2_id, "1"))
    expect(set(coins_of(again2)) & set(coins_of(lost2_tx)), "the new payment spends no coin of the kept one")
    expect(not node_takes(lost2), "the node took the second kept transaction beside the new payment")
    bench("mine", 1)
    expect(not node_takes(lost2), "the node took the second kept transaction after the new payment was in a block")
    gone_after_the_block()
    expect(amount("Can be spent") == "52.99999348", "at the end: " + amount("Can be spent"))
    step("when the server lists the coin again, the next payment is built on it, and the kept transaction can no longer arrive: 52.99999348 NORTH are left")
    print("A SERVER THAT SAYS NO: ALL %d STEPS PASSED on Android %s" % (len(done), p.release()), flush=True)
finally:
    switches('{"refuse": "", "hide": false}')
