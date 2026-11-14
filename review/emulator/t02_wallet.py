#!/usr/bin/env python3
"""The wallet from first start to restore, on an emulator, against the test bench (bench.py).

  t02_wallet.py SERIAL APK own|public

The emulator must be running with a screen lock and a fingerprint (lock_and_finger.py) and must trust the test
authority (trust_ca.sh). The app is installed fresh. Every step says what it expects; the first surprise stops the run.
"""
import json
import os
from decimal import Decimal
import re
import sys
import time

from emu import Phone
import flows
from flows import bench, bench_state, tab, top, refresh, scroll_to, number_after

PKG = "org.polarka.wallet"
serial, apk, mode = sys.argv[1], sys.argv[2], sys.argv[3]
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


print("Android %s, %s, node: %s" % (p.release(), os.path.basename(apk), mode), flush=True)
p.adb("uninstall", PKG)
out = p.adb("install", apk, timeout=180)
expect("Success" in out, "the package was not installed: %s" % out[-200:])
p.start(PKG + "/.MainActivity")
p.wait("Connect to a public node", 30)
expect(p.has("does not run a public node of its own yet"), "the first screen does not say that the project has no public node yet")
expect(not p.has("node.polarka.org"), "the first screen names a public node that does not exist")
scroll_to(p, "Connect to a node of your own")
step("installed and started; the first screen offers a node of one's own and names no public node, because the project has none yet")

scroll_to(p, "Run the self-test")
p.tap("Run the self-test")
p.wait("Passed:", 30)
step("self-test passed: this Android derives and signs like the published vectors")
top(p)

# ------------------------------------------------------------------ the node
if mode == "public":
    path = os.path.join(st["work"], "setup-public.json")
    with open(path, "w") as f:
        json.dump({"format": "polarka-setup/2", "node": {"url": st["public_url"]}, "chain": "regtest", "addresses": []}, f)
else:
    path = bench("setup", "own", st["bench_address"])
flows.import_setup(p, path)
expect(p.has("10.0.2.2"), "the node of the setup is not shown")
if mode == "public":
    expect(p.has("none (public node)") and p.has("A public node sees your addresses"), "the public node is not described as one")
else:
    expect(p.has("not shown") and not p.has(open(os.path.join(st["work"], "phone-password")).read().strip()), "the password is on the screen")
    expect(p.has("is treated as a node of your own"), "the owner is not told what a node with an account is given")
p.tap("Save and connect")
p.wait("Total", 40, exact=True)
step("setup imported from a file and saved; the node answers over TLS")
if mode == "own":
    expect(p.has("The setup file was deleted"), "the setup file with the password was not deleted")
    p.tap("OK", exact=True)

# ------------------------------------------------------------------ a new wallet
words_a = flows.make_wallet(p)
step("wallet A made: 24 words shown once, three asked back, sealed with the fingerprint")
flows.check_paper(p, words_a)
flows.answer(p, "They match")
expect(not p.has("Confirm that it is you"), "the check of the paper asked for the fingerprint")
flows.check_paper(p, ["abandon"] * 23 + ["art"])
flows.answer(p, "of another wallet")
tab(p, "Overview")
step("the whole paper checked against the wallet without the fingerprint: its 24 words match, valid words of another wallet do not")
address_1 = flows.receive_address(p)
expect(address_1.startswith("rnorth1z"), "the receiving address has another form: " + address_1)
bench("pay", address_1, 25)
bench("mine", 1)
fresh_look()
expect(amount("Can be spent") == "25.00000000", "25 NORTH did not arrive: " + amount("Can be spent"))
address_2 = flows.receive_address(p)
expect(address_2 != address_1, "a used address is still offered for the next payment")
step("received 25 NORTH; the wallet moved on to a fresh address")

# ------------------------------------------------------------------ a payment, and the app closed while it waits
txid = flows.send(p, st["bench_address"], "5")
expect(txid in bench("call", "getrawmempool"), "the node does not hold the payment")
tab(p, "Overview")
top(p)
p.wait("Sent, waiting for a block", 20)
expect(amount("In payments waiting for a block") == "25.00000000", "the coin of the waiting payment is not set aside")
p.stop(PKG)
p.start(PKG + "/.MainActivity")
flows.unlock_if_locked(p)
p.wait("Total", 40, exact=True)
expect(p.has("Sent, waiting for a block") and p.has(txid), "after the app was closed the waiting payment is forgotten")
step("sent 5 NORTH with the fingerprint; the app was closed and still knows the waiting payment")
tab(p, "Send")
top(p)
p.tap("Recipient address")
p.type(st["bench_address"])
p.tap("Amount (NORTH)")
p.type("3")
p.back()
scroll_to(p, "Check the payment")
p.tap("Check the payment")
p.wait("There is nothing that can be spent now", 30)
step("a second payment on the same coin is refused while the first one waits")
bench("mine", 1)
fresh_look()
expect(amount("Can be spent") == "19.99999674", "after the block: " + amount("Can be spent"))
expect(not p.has("Sent, waiting for a block"), "the payment still shows as waiting after its block")
tab(p, "History")
expect(p.has("paid") and p.has("received"), "the history lacks the payment or the receipt")
step("after the block: 19.99999674 NORTH, history shows both movements")

# ------------------------------------------------------------------ a paper address: wallet A's words emptied into wallet B
bench("pay", address_1, 7)
bench("mine", 1)
fresh_look()
expect(amount("Can be spent") == "26.99999674", "the old address no longer receives: " + amount("Can be spent"))
flows.remove_wallet_04(p)
step("an address that was used still receives; wallet A removed after the fingerprint")
words_b = flows.make_wallet(p)
tab(p, "More")
field = flows.field_of(p, "Empty a paper address")
p.tap_xy(*field.center)
time.sleep(0.6)
flows.type_words(p, words_a)
button = flows.seek(p, "Look at the address")
p.tap_xy(*button.center)
flows.seek(p, "To this wallet")
flows.seek(p, "Can be spent", exact=True)
expect(amount("Can be spent") == "26.99999674", "the paper look shows " + amount("Can be spent"))
button = flows.seek(p, "Check the payment", exact=True)
p.tap_xy(*button.center)
flows.seek(p, "This empties the paper address completely")
flows.seek(p, "Fee", exact=True)
fee = Decimal(amount("Fee"))
left = "%.8f" % (Decimal("26.99999674") - fee)
before = bench("call", "getrawmempool")
button = flows.seek(p, "Sign and send", exact=True)
p.tap_xy(*button.center)
for _ in range(40):
    if bench("call", "getrawmempool") != before:
        break
    time.sleep(1)
expect(bench("call", "getrawmempool") != before, "the emptying of the paper address did not reach the node")
bench("mine", 1)
fresh_look()
expect(amount("Can be spent") == left, "wallet B after emptying the paper: %s, expected %s" % (amount("Can be spent"), left))
step("the words of wallet A, typed as a paper address, were emptied into wallet B: %s NORTH (fee %s)" % (left, fee))

# ------------------------------------------------------------------ restore
flows.remove_wallet_04(p)
said = flows.restore_wallet_with_a_slip(p, words_b)
p.wait("The wallet is ready", 20)
p.tap("OK", exact=True)
fresh_look(6)
expect(amount("Can be spent") == left, "wallet B brought back from its words shows " + amount("Can be spent"))
step("wallet B removed and brought back from its 24 words, after a slip in the last word was named (%s) and put right: the coins are found again" % said.split(": ")[1])

print("ALL %d STEPS PASSED on Android %s (%s node)" % (len(done), p.release(), mode), flush=True)
