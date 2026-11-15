#!/usr/bin/env python3
"""The first screen as it is handed out: the wallet server that is filled in answers this Android over TLS.

  t06_real_server.py SERIAL APK

This is the one run that touches a real network, and it is not part of matrix.sh: start it on purpose. The
app is installed fresh and has no wallet and no watched address, so the server is named no address at all;
the app asks it for the height of the chain and nothing else. The height on the screen is compared with what
the same server tells this machine at that moment. Then the setup is removed again.

It shows that the Android under test accepts the certificate of the server named in the app and reads its
answer. It shows nothing about balances or payments through that server.
"""
import json
import os
import re
import sys
import time
import urllib.request

from emu import Phone
import flows
from flows import scroll_to, top

PKG = "org.polarka.wallet"
SERVER = "https://api.northboundwallet.org"
serial, apk = sys.argv[1], sys.argv[2]
p = Phone(serial)
done = []


def step(text):
    done.append(text)
    print("ok    %2d  %s" % (len(done), text), flush=True)


def expect(condition, what):
    if not condition:
        p.show("at the failure")
        raise SystemExit("FAILED after step %d: %s" % (len(done), what))


def height_here():
    with urllib.request.urlopen(SERVER + "/v1/chain/tip", timeout=30) as answer:
        return int(json.loads(answer.read().decode())["height"])


print("Android %s, %s, the server named in the app" % (p.release(), os.path.basename(apk)), flush=True)
p.adb("uninstall", PKG)
out = p.adb("install", apk, timeout=180)
expect("Success" in out, "the package was not installed: %s" % out[-200:])
p.start(PKG + "/.MainActivity")
flows.first_screen(p)
expect(p.has(SERVER, exact=True), "the first screen does not fill in %s" % SERVER)
button = flows.seek(p, "Continue", exact=True)
p.tap_xy(*button.center)
flows.wait_any(p, ("Check before saving",), 20)
expect(p.has("Server", exact=True) and p.has(SERVER, exact=True) and p.has("main", exact=True),
       "the check before saving does not show the server of the first screen on the main network")
step("the first screen fills in %s, and the check before saving shows it as a wallet server on the main network" % SERVER)

before = height_here()
p.tap("Save and connect")
p.wait("Total", 60, exact=True)
line = flows.seek(p, "The server answers, block")
shown = int(re.search(r"block (\d+)", line.label).group(1))
after = height_here()
expect(before - 1 <= shown <= after + 1, "the app shows block %d, this machine was told %d and then %d" % (shown, before, after))
expect(not p.has("cannot be reached") and not p.has("answered with an error"), "the app reports a problem with the server")
step("this Android accepted the server's certificate and read its answer: block %d (this machine was told %d before and %d after)" % (shown, before, after))

flows.remove_setup(p, wallet=False)
top(p)
scroll_to(p, "Connect to a node of your own")
p.adb("uninstall", PKG)
step("the setup was removed again and the app taken off; no wallet was made, so the server was named no address")
print("REAL SERVER: ALL %d STEPS PASSED on Android %s" % (len(done), p.release()), flush=True)
