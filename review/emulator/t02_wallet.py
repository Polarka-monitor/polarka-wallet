#!/usr/bin/env python3
"""The wallet from first start to restore, on an emulator, against the test bench (bench.py).

  t02_wallet.py SERIAL APK own|public|server

own: a node of one's own with an account. public: a public node without one (the gateway). server: a wallet
server (the stand-in for a Northbound server); that run makes its wallets with twelve words, the other two with
twenty-four.

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
    flows.asking_gone(p)


if mode not in ("own", "public", "server"):
    raise SystemExit("the third argument is own, public or server")
print("Android %s, %s, connection: %s" % (p.release(), os.path.basename(apk), mode), flush=True)
p.adb("uninstall", PKG)
out = p.adb("install", apk, timeout=180)
expect("Success" in out, "the package was not installed: %s" % out[-200:])
p.start(PKG + "/.MainActivity")
flows.first_screen(p)
expect(p.has("Connect through a public server") and p.has("api.northboundwallet.org"),
       "the first screen does not open with the wallet server that the app offers")
expect(p.has("run by the Northbound project, not by the authors of this app"), "the first screen does not say whose server that is")
expect(p.has("The server sees all the addresses of your wallet together") and p.has("It cannot take your coins"),
       "the first screen does not say what a wallet server sees and what it cannot do")
expect(not p.has("node.polarka.org"), "the first screen names a node of the project, which has none")
scroll_to(p, "Connect to a node of your own")
step("installed and started; the first screen offers the wallet server of the Northbound project, says whose it is and what it sees, and below it a node of one's own")

scroll_to(p, "Run the self-test")
p.tap("Run the self-test")
p.wait("Passed:", 30)
step("self-test passed: this Android derives and signs like the published vectors")
top(p)

# ------------------------------------------------------------------ the node, or the wallet server
count = 12 if mode == "server" else 24
other_words = ["abandon"] * 23 + ["art"] if count == 24 else ["abandon"] * 11 + ["about"]
if mode == "server":
    seen_at_start = json.loads(bench("seen"))
    # The address is typed over the one that is filled in, as an owner with another server would do it. A server
    # named like that is taken to work for the main network, and the bench is a test network: the app has to
    # notice and say so.
    flows.connect_to_server(p, st["server_url"])
    expect(p.has("Server", exact=True) and p.has(st["server_url"], exact=True), "the check before saving does not show the server that was typed")
    expect(p.has("main", exact=True), "a server typed by hand is not taken to work for the main network")
    expect(not p.has("User", exact=True) and not p.has("Password", exact=True), "a wallet server is shown with an account")
    expect(p.has("The server sees all the addresses of your wallet together"), "the check before saving does not say what the server sees")
    p.tap("Save and connect")
    p.wait("Total", 40, exact=True)
    flows.seek(p, "The server answers, block %s" % bench("call", "getblockcount"))
    step("another server address typed over the one filled in, checked before saving and saved: the server answers over TLS")
    top(p)
    flows.make_wallet(p, 12)
    fresh_look()
    p.wait("The server did not take what the wallet asked (address belongs to a different network)", 60)
    expect(p.has("It probably works for another network than the setup says"), "the app does not say what is probably wrong with the server")
    expect(flows.receive_address(p).startswith("north1z"), "the wallet made with a server typed by hand is not one of the main network")
    flows.remove_wallet_04(p)
    flows.remove_setup(p, wallet=False)
    step("a wallet of 12 words made with it: the server works for another network than the wallet, and the app says so in words; wallet and setup removed again")
    path = bench("setup", "server")
elif mode == "public":
    path = os.path.join(st["work"], "setup-public.json")
    with open(path, "w") as f:
        json.dump({"format": "polarka-setup/2", "node": {"url": st["public_url"]}, "chain": "regtest", "addresses": []}, f)
else:
    path = bench("setup", "own", st["bench_address"])
flows.import_setup(p, path)
expect(p.has("10.0.2.2"), "the node of the setup is not shown")
if mode == "server":
    expect(p.has("Server", exact=True) and p.has("regtest", exact=True), "the setup file is not shown as a wallet server on the test network")
    expect(not p.has("Delete the setup file"), "a server setup holds nothing secret, and yet deleting the file is offered")
    expect(p.has("The server sees all the addresses of your wallet together"), "the check before saving does not say what the server sees")
elif mode == "public":
    expect(p.has("none (public node)") and p.has("A public node sees your addresses"), "the public node is not described as one")
else:
    expect(p.has("not shown") and not p.has(open(os.path.join(st["work"], "phone-password")).read().strip()), "the password is on the screen")
    expect(p.has("is treated as a node of your own"), "the owner is not told what a node with an account is given")
p.tap("Save and connect")
p.wait("Total", 40, exact=True)
if mode == "server":
    step("a setup file that names a wallet server imported and saved; it holds nothing secret, so the file is left alone; the server answers over TLS")
else:
    step("setup imported from a file and saved; the node answers over TLS")
if mode == "own":
    expect(p.has("The setup file was deleted"), "the setup file with the password was not deleted")
    p.tap("OK", exact=True)

# ------------------------------------------------------------------ a new wallet
words_a = flows.make_wallet(p, count)
step("wallet A made: %d words shown once, three asked back, sealed with the fingerprint" % count)
flows.check_paper(p, words_a)
flows.answer(p, "They match")
expect(not p.has("Confirm that it is you"), "the check of the paper asked for the fingerprint")
flows.check_paper(p, other_words)
flows.answer(p, "of another wallet")
tab(p, "Overview")
step("the whole paper checked against the wallet without the fingerprint: its %d words match, valid words of another wallet do not" % count)
address_1 = flows.receive_address(p)
expect(address_1.startswith("rnorth1z"), "the receiving address has another form: " + address_1)
bench("pay", address_1, 25)
if mode == "server":
    fresh_look()
    expect(amount("On its way, waiting for a block") == "25.00000000", "a payment that waits for a block is not shown as on its way")
    expect(amount("Can be spent") == "0.00000000", "a payment that waits for a block counts as spendable: " + amount("Can be spent"))
    step("25 NORTH that wait for a block are shown as on their way, apart from what can be spent")
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
if mode == "server":
    # a server does not list a coin that a waiting payment spends: nothing can be spent, and the change is on its way
    fresh_look()
    expect(p.has("Sent, waiting for a block"), "after a look the payment no longer shows as waiting")
    expect(amount("Can be spent") == "0.00000000", "the coin of the waiting payment still counts as spendable: " + amount("Can be spent"))
    expect(amount("On its way, waiting for a block") == "19.99999674", "the change of the waiting payment is not shown as on its way")
else:
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
words_b = flows.make_wallet(p, count)
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
top(p)          # the notice stands at the top of the page, and the words were typed further down
p.wait("The wallet is ready", 20)
p.tap("OK", exact=True)
fresh_look(6)
expect(amount("Can be spent") == left, "wallet B brought back from its words shows " + amount("Can be spent"))
step("wallet B removed and brought back from its %d words, after a slip in the last word was named (%s) and put right: the coins are found again" % (count, said.split(": ")[1]))

# ------------------------------------------------------------------ what only a wallet server does
if mode == "server":
    bench("standin", '{"busy": 3}')
    fresh_look(8)
    expect(not p.has("The server is busy") and amount("Can be spent") == left, "three requests to slow down were not waited out")
    step("a server that asks three times to slow down is waited for and asked again: the look succeeds without a word")
    bench("standin", '{"stale": true}')
    tab(p, "Overview")
    top(p)
    refresh(p, 2)
    p.wait("The server is busy. Try again in a moment.", 90)
    bench("standin", '{"stale": false}')
    fresh_look(6)
    expect(not p.has("The server is busy") and amount("Can be spent") == left, "after the server caught up the balance is not back")
    step("a server that stays behind its node is called busy once the app has waited for it; when it has caught up, the balance is back")
    tab(p, "Mining")
    flows.seek(p, "The server does not tell the hash rate of the network")
    step("the mining page says what a wallet server does not tell")
    seen_at_end = json.loads(bench("seen"))
    print("note      the wallet asked the server %d times over %d connections" % (
        seen_at_end["server_requests"] - seen_at_start["server_requests"],
        seen_at_end["connections"]["server"] - seen_at_start["connections"]["server"]), flush=True)

kind = {"own": "own node", "public": "public node", "server": "wallet server"}[mode]
print("ALL %d STEPS PASSED on Android %s (%s)" % (len(done), p.release(), kind), flush=True)
