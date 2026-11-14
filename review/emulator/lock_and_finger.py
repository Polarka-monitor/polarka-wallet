#!/usr/bin/env python3
"""Gives a fresh emulator a screen lock (PIN 1234) and one enrolled fingerprint, the way a user would in Settings.

Usage: lock_and_finger.py emulator-5560 [-v]
The Settings screens differ between Android versions, so this walks them by what is written on them.
"""
import sys
import time
from emu import Phone

PIN = "1234"
serial = sys.argv[1]
verbose = "-v" in sys.argv
p = Phone(serial)
print("Android", p.release())

print(p.sh("locksettings set-pin %s" % PIN).strip() or "pin set (no message)")
if "1" not in p.sh("cmd lock_settings get-disabled 2>/dev/null || true") and verbose:
    pass
p.sh("am start -a android.settings.FINGERPRINT_ENROLL")
time.sleep(2)

FORWARD = ["I agree", "Agree", "More", "Next", "Start", "Continue", "Got it", "OK"]
TOUCH = ["touch the sensor", "touch & hold", "lift, then touch", "keep lifting", "touch the fingerprint", "place your finger",
         "lift finger", "touch sensor", "put your finger"]
done = False
for step in range(60):
    nodes = p.nodes()
    labels = [n.label for n in nodes if n.label]
    low = " | ".join(labels).lower()
    if verbose:
        print("step %d: %s" % (step, labels[:14]))
    if "fingerprint added" in low or ("done" in [l.lower() for l in labels] and "fingerprint" in low and "agree" not in low):
        found = p.find("Done", exact=True, nodes=nodes)
        if found:
            p.tap_xy(*found[0].center)
        done = True
        break
    if any(n.cls.endswith("EditText") for n in nodes) and ("pin" in low or "password" in low):
        p.type(PIN)
        p.key("KEYCODE_ENTER")
        time.sleep(1.5)
        continue
    if any(t in low for t in TOUCH):
        p.finger(1)
        time.sleep(0.8)
        continue
    for word in FORWARD:
        found = [n for n in p.find(word, exact=True, nodes=nodes) if n.enabled]
        if found:
            p.tap_xy(*found[0].center)
            time.sleep(1.2)
            break
    else:
        # Nothing recognised: on some versions the consent text must be scrolled before the button appears.
        p.swipe_up()
        time.sleep(0.8)

print("fingerprint enrolled:", done)
p.key("KEYCODE_HOME")
print(p.sh("dumpsys fingerprint | grep -i -m3 -E 'count|enrolled|prints'").strip()[:400])
sys.exit(0 if done else 1)
