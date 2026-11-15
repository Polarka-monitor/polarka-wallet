#!/usr/bin/env python3
"""A wallet server that changed the shape of its answers: version 0.6.0, then the package under test over it.

  t08_server_changed.py SERIAL APK_0_6_0 NEW_APK

On 15 November 2026 the public Northbound server changed the lines of its history and began to list coins a
page at a time. Version 0.6.0 reads the earlier answers. Here the signed package of 0.6.0 is connected to the
bench's wallet server, which answers in the new shapes, and a wallet is made and paid: the app has to say that
it cannot read the server, and must not show coins from an answer it refused. Then the package under test is
installed over it. It must show the wallet and its history; a payment made with it must show its change as
on its way while it waits; and with three coins to a page every coin must still be found.

The emulator must be running with a screen lock and a fingerprint and must trust the test authority, as for
t02_wallet.py.
"""
import os
import sys

from emu import Phone
import flows
from flows import bench, bench_state, number_after

PKG = "org.polarka.wallet"
serial, old_apk, new_apk = sys.argv[1], sys.argv[2], sys.argv[3]
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


def fresh_look(seconds=6):
    flows.tab(p, "Overview")
    flows.top(p)
    flows.refresh(p, seconds)
    flows.asking_gone(p)


def version():
    for line in p.sh("dumpsys package %s" % PKG).splitlines():
        if "versionName=" in line:
            return line.split("versionName=")[1].strip()
    return "?"


print("Android %s, %s with a server that answers in the new shapes, then %s over it" % (
    p.release(), os.path.basename(old_apk), os.path.basename(new_apk)), flush=True)
bench("standin", '{"page": 0}')
p.adb("uninstall", PKG)
out = p.adb("install", old_apk, timeout=180)
expect("Success" in out, "the old package was not installed: %s" % out[-200:])
was = version()
expect(was == "0.6.0", "the first package is not version 0.6.0 but " + was)
p.start(PKG + "/.MainActivity")
flows.first_screen(p)
flows.import_setup(p, bench("setup", "server"))
expect(p.has("Server", exact=True) and p.has("regtest", exact=True), "the setup file is not shown as a wallet server on the test network")
p.tap("Save and connect")
p.wait("Total", 40, exact=True)
flows.seek(p, "The server answers, block %s" % bench("call", "getblockcount"))
flows.top(p)
flows.make_wallet(p, 12)
address = flows.receive_address(p)
fresh_look()
expect(not p.has("The server answered with an error"), "version 0.6.0 cannot read the server even about a wallet that has no history")
step("version %s and a wallet server that answers in the new shapes: the server answers, and a new wallet without any history is shown" % was)

bench("pay", address, 25)
bench("mine", 1)
fresh_look()
p.wait("The server answered with an error", 60)
expect(p.has("unknown form"), "version 0.6.0 does not say that it could not read the answer")
expect(not p.has("25.00000000"), "version 0.6.0 shows coins from an answer that it refused")
p.show("what version %s shows of a wallet with history" % was)
step("25 NORTH paid to the wallet: version %s says that the server answered with an error, an answer in an unknown form, and shows none of the coins" % was)

p.stop(PKG)
out = p.adb("install", "-r", new_apk, timeout=180)
expect("Success" in out, "the new package was not installed over the old one: %s" % out[-300:])
now = version()
expect(now != was, "the version did not change: " + now)
p.start(PKG + "/.MainActivity")
flows.unlock_if_locked(p)
p.wait("Total", 40, exact=True)
fresh_look()
expect(not p.has("The server answered with an error"), "version %s does not read the server either" % now)
expect(amount("Can be spent") == "25.00000000", "version %s shows %s where 25 NORTH are" % (now, amount("Can be spent")))
flows.tab(p, "History")
expect(p.has("received"), "the history lacks the payment that came in")
step("version %s installed over it: the same wallet opens after the fingerprint and shows its 25 NORTH and the payment in its history" % now)

txid = flows.send(p, st["bench_address"], "5")
expect(txid in bench("call", "getrawmempool"), "the node behind the server does not hold the payment")
fresh_look()
expect(p.has("Sent, waiting for a block") and p.has(txid), "the payment does not show as waiting for a block")
flows.seek(p, "On its way, waiting for a block")
expect(amount("On its way, waiting for a block") == "19.99999674", "the change of the waiting payment does not show as on its way: " + amount("On its way, waiting for a block"))
step("a payment of 5 NORTH made with version %s waits for a block: its coin is gone from the server's list, and its change of 19.99999674 NORTH shows as on its way" % now)

bench("mine", 1)
fresh_look()
expect(amount("Can be spent") == "19.99999674", "after the block: " + amount("Can be spent"))
expect(not p.has("On its way, waiting for a block"), "something still shows as on its way after the block")
flows.tab(p, "History")
expect(p.has("paid") and p.has("received"), "the history lacks the payment or the receipt")
step("after the block: 19.99999674 NORTH can be spent, nothing is on its way, and the history has both movements")

bench("standin", '{"page": 3}')
for _ in range(4):
    bench("pay", address, 1)
bench("mine", 1)
fresh_look()
found = amount("Can be spent")
bench("standin", '{"page": 0}')
expect(found == "23.99999674", "with three coins to a page the wallet shows " + found)
step("four more payments of 1 NORTH, and a server that lists three coins to a page: the wallet's five coins are read from two pages, 23.99999674 NORTH")
print("A SERVER THAT CHANGED ITS ANSWERS: ALL %d STEPS PASSED (%s -> %s)" % (len(done), was, now), flush=True)
