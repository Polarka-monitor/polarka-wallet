#!/usr/bin/env python3
"""A package installed over an older one keeps the wallet: an older signed package, then the package under test.

  t05_upgrade.py SERIAL OLD_APK NEW_APK

With the old package a wallet is made, receives, and sends a payment that is left waiting for a block. The new
package is installed over it (both are signed with the same key, or Android refuses). It must open the same
wallet after the fingerprint, still know the waiting payment, and sign a new payment with the words that the old
package stored, and it must find the paper that was written with the old package right (the check of the
whole paper, since 0.5.1). The screens of 0.4.1 differ from the later ones, so with an old package of 0.4 the
first part uses the steps as they were then (flows_0_4.py).

The emulator must be running with a screen lock and a fingerprint and must trust the test authority, as for
t02_wallet.py.
"""
import os
import sys
import time

from emu import Phone
import flows
import flows_0_4
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


def fresh_look(steps, seconds=4):
    steps.tab(p, "Overview")
    steps.top(p)
    steps.refresh(p, seconds)
    p.wait("Asking the node", 30, gone=True)


def version():
    for line in p.sh("dumpsys package %s" % PKG).splitlines():
        if "versionName=" in line:
            return line.split("versionName=")[1].strip()
    return "?"


print("Android %s, %s, then %s over it" % (p.release(), os.path.basename(old_apk), os.path.basename(new_apk)), flush=True)
p.adb("uninstall", PKG)
out = p.adb("install", old_apk, timeout=180)
expect("Success" in out, "the old package was not installed: %s" % out[-200:])
was = version()
old = flows_0_4 if was.startswith("0.4") else flows
p.start(PKG + "/.MainActivity")
p.wait("Connect to a public node", 30)
old.import_setup(p, bench("setup", "own", st["bench_address"]))
p.tap("Save and connect")
p.wait("Total", 40, exact=True)
if p.has("The setup file was deleted"):
    p.tap("OK", exact=True)
words = old.make_wallet(p)
address = old.receive_address(p)
bench("pay", address, 25)
bench("mine", 1)
fresh_look(old)
expect(amount("Can be spent") == "25.00000000", "25 NORTH did not arrive: " + amount("Can be spent"))
txid = old.send(p, st["bench_address"], "5")
expect(txid in bench("call", "getrawmempool"), "the node does not hold the payment")
step("version %s: a wallet with 25 NORTH and a payment of 5 NORTH that waits for a block" % was)

p.stop(PKG)
out = p.adb("install", "-r", new_apk, timeout=180)
expect("Success" in out, "the new package was not installed over the old one: %s" % out[-300:])
now = version()
expect(now != was, "the version did not change: " + now)
p.start(PKG + "/.MainActivity")
flows.unlock_if_locked(p)
p.wait("Total", 40, exact=True)
expect(p.has("Sent, waiting for a block") and p.has(txid), "after the new package was installed the waiting payment is forgotten")
expect(amount("In payments waiting for a block") == "25.00000000", "the coin of the waiting payment is not set aside: " + amount("In payments waiting for a block"))
step("version %s installed over it: the same wallet opens after the fingerprint and still knows the waiting payment" % now)

bench("mine", 1)
fresh_look(flows)
expect(amount("Can be spent") == "19.99999674", "after the block: " + amount("Can be spent"))
expect(not p.has("Sent, waiting for a block"), "the payment still shows as waiting after its block")
second = flows.send(p, st["bench_address"], "3")
expect(second in bench("call", "getrawmempool"), "the node does not hold the payment made with the new package")
bench("mine", 1)
fresh_look(flows)
expect(amount("Can be spent") == "16.99999348", "after the second block: " + amount("Can be spent"))
flows.tab(p, "History")
expect(p.has("paid") and p.has("received"), "the history lacks the payments or the receipt")
step("the new package signs with the words the old one stored: a payment of 3 NORTH went through, 16.99999348 NORTH are left")
flows.check_paper(p, words)
flows.answer(p, "They match")
step("the paper written with version %s is found right by version %s" % (was, now))
print("UPGRADE: ALL %d STEPS PASSED (%s -> %s)" % (len(done), was, now), flush=True)
