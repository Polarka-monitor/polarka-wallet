#!/usr/bin/env python3
"""The lock of the wallet on an emulator, after t02_wallet.py has left a wallet in the app.

  t03_lock.py SERIAL [keygone]

1. More than a minute in the background: the app asks for the fingerprint again.
2. The same, with Android building the screen anew meanwhile (dark mode switched while the app is away):
   finding F-04 of the independent review, here on a (virtual) phone.
3. With "keygone": the screen lock of the phone is removed. Android then destroys the key that protects the
   recovery words; the app must say so in plain words when a payment is to be signed. This ruins the wallet in
   the emulator and leaves the emulator without a screen lock.
"""
import sys
import time

from emu import Phone
import flows
from flows import tab, top, scroll_to

PKG = "org.polarka.wallet"
serial = sys.argv[1]
keygone = "keygone" in sys.argv[2:]
p = Phone(serial)
n = 0


def step(text):
    global n
    n += 1
    print("ok    %2d  %s" % (n, text), flush=True)


def expect(condition, what):
    if not condition:
        p.show("at the failure")
        raise SystemExit("FAILED after step %d: %s" % (n, what))


def away(seconds, between=None):
    p.key("KEYCODE_HOME")
    time.sleep(2)
    if between:
        between()
    time.sleep(seconds)
    p.start(PKG + "/.MainActivity")
    time.sleep(2.5)


print("Android %s" % p.release(), flush=True)
p.start(PKG + "/.MainActivity")
flows.unlock_if_locked(p)
p.wait("Total", 40, exact=True)

away(20)
expect(p.has("Total", exact=True) and not p.has("Confirm that it is you"), "the app locked after twenty seconds away")
step("twenty seconds away: the wallet stays open")

away(66)
expect(p.has("Confirm that it is you") or p.has("The wallet is locked"), "after more than a minute away the wallet is open without a question")
expect(not p.has("Can be spent"), "the balance shows through the lock")
flows.unlock_if_locked(p)
p.wait("Total", 40, exact=True)
step("more than a minute away: locked, and opened again with the fingerprint")

night = "yes" in p.sh("cmd uimode night")
away(66, between=lambda: p.sh("cmd uimode night %s" % ("no" if night else "yes")))
locked = p.has("Confirm that it is you") or p.has("The wallet is locked")
p.sh("cmd uimode night %s" % ("yes" if night else "no"))
expect(locked, "F-04: Android rebuilt the screen while the app was away, and the wallet is open without a question")
time.sleep(2)
flows.unlock_if_locked(p)
p.wait("Total", 40, exact=True)
step("more than a minute away with the screen rebuilt meanwhile (dark mode switched): locked as well")

if keygone:
    print(p.sh("locksettings clear --old 1234").strip(), flush=True)
    time.sleep(3)
    p.stop(PKG)
    p.start(PKG + "/.MainActivity")
    time.sleep(4)
    p.show("after the screen lock was removed")
    step("the screen lock of the phone was removed; see the screen above for what the app says")

print("LOCK STEPS PASSED: %d on Android %s" % (n, p.release()), flush=True)
