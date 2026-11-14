#!/usr/bin/env python3
"""What a user does in the wallet, step by step, as the emulator tests repeat it. English screens."""
import json
import os
import re
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))


def bench(*args):
    r = subprocess.run([sys.executable, os.path.join(HERE, "bench.py")] + [str(a) for a in args],
                       capture_output=True, text=True, timeout=900)
    if r.returncode:
        raise RuntimeError("bench %s: %s" % (args[0], (r.stderr or r.stdout).strip()[:300]))
    return r.stdout.strip()


def bench_state():
    with open(os.path.join(HERE, "run" + os.environ.get("BENCH", ""), "current.json")) as f:
        return json.load(f)


PAGES = ("Overview", "Receive", "Send", "History", "More")
# what of the screen shows the page itself: below the bar at the top, and above the bar with the pages at the
# bottom where there is one (the first screens and the lock screen have none)
VISIBLE_TOP = 250
SCREEN_BOTTOM = 2400        # the virtual phones are 1080 x 2400


def visible(nodes):
    """Which nodes can be touched: those that lie in the part of the screen that shows the page."""
    floor = max((n.box[3] for n in nodes), default=2400)
    bar = [n.box[1] for n in nodes if n.label in PAGES and n.box[1] > floor * 0.8]
    # without that bar: keep off the last 150 of the glass, where the phone's own bar may lie, and off the very
    # edge of what the app shows (on Android 12 here the page ends at 2274, above the phone's bar)
    bottom = (min(bar) - 10) if len(bar) >= 3 else min(floor - 20, SCREEN_BOTTOM - 150)
    return [n for n in nodes if VISIBLE_TOP < n.center[1] < bottom]


def tab(p, name):
    """Opens a page from the bar at the bottom of the screen (since 0.5.0). The mining page is opened from More."""
    if name == "Mining":
        tab(p, "More")
        p.tap_xy(*seek(p, "Open the mining page").center)
        time.sleep(1.2)
        return
    for attempt in range(6):
        nodes = p.nodes()
        floor = max((n.box[3] for n in nodes), default=2400)
        bar = [n for n in nodes if n.label == name and n.box[1] > floor * 0.8]
        if bar:
            p.tap_xy(*max(bar, key=lambda n: n.box[1]).center)
            time.sleep(1.2)
            return
        hide_keyboard(p)            # an open keyboard covers the bar
        time.sleep(0.7)
    raise RuntimeError("no page %r in the bar at the bottom; on screen: %s" % (name, p.texts()[:12]))


def scroll_to(p, text, tries=8, exact=False):
    for _ in range(tries):
        found = p.find(text, exact, nodes=visible(p.nodes()))
        if found:
            return found[0]
        p.swipe_up()
    raise RuntimeError("%r is not on the screen; there is: %s" % (text, p.texts()[:30]))


def top(p):
    p.swipe_down(3)


def refresh(p, seconds=4):
    p.tap("Refresh", exact=True)
    time.sleep(seconds)


def number_after(p, label, index=0):
    """The amount written to the right of a label on the same line."""
    nodes = p.nodes()
    rows = [n for n in nodes if n.label == label]
    if len(rows) <= index:
        raise RuntimeError("no line %r; on screen: %s" % (label, [n.label for n in nodes if n.label][:40]))
    y = rows[index].center[1]
    same = [n for n in nodes if n.label and n is not rows[index] and abs(n.center[1] - y) < 30 and n.box[0] > rows[index].box[0]]
    if not same:
        raise RuntimeError("nothing next to %r" % label)
    return same[0].label.replace(" ", "").replace(" ", "")


def read_words(p):
    found = {}
    for t in p.texts():
        m = re.match(r"^\s*(\d+)\.\s+([a-z]+)$", t)
        if m:
            found[int(m.group(1))] = m.group(2)
    if sorted(found) != list(range(1, len(found) + 1)) or len(found) not in (12, 24):
        raise RuntimeError("the words are not all on the screen: %s" % sorted(found))
    return [found[i] for i in sorted(found)]


def tick(p, label):
    """Ticks the box that stands left of a label."""
    nodes = p.nodes()
    lab = p.find(label, nodes=nodes)[0]
    boxes = [n for n in nodes if n.cls.endswith("CheckBox") and abs(n.center[1] - lab.center[1]) < 60]
    if boxes:
        if not boxes[0].checked:
            p.tap_xy(*boxes[0].center)
    else:
        p.tap_xy(*lab.center)
    time.sleep(0.5)


def fingerprint(p, wait=10):
    p.wait("Confirm that it is you", wait)
    time.sleep(0.8)
    p.finger(1)
    p.wait("Confirm that it is you", 15, gone=True)
    time.sleep(1.5)


def make_wallet(p):
    """Makes a new wallet from the overview or from More. Returns its recovery words."""
    scroll_to(p, "Make a new wallet")
    p.tap("Make a new wallet")
    p.wait("Your recovery words", 15)
    words = read_words(p)
    tick(p, "I have written all")
    p.tap("Continue", exact=True)
    p.wait("Check your paper", 10)
    for lab in [n for n in p.nodes() if n.label.startswith("Word number")]:
        k = int(lab.label.split()[-1])
        p.tap_xy(*lab.center)
        time.sleep(0.4)
        p.type(words[k - 1])
    hide_keyboard(p)
    scroll_to(p, "Lock the wallet with my fingerprint")
    p.tap("Lock the wallet with my fingerprint")
    fingerprint(p)
    p.wait("The wallet is ready", 20)
    hide_keyboard(p)
    p.tap("OK", exact=True)
    return words


def remove_wallet(p):
    tab(p, "More")
    top(p)
    scroll_to(p, "Remove the wallet from this phone")
    p.tap("Remove the wallet from this phone")
    p.wait("Remove the wallet?", 10)
    p.tap("I have the words, remove")
    p.wait("The wallet was removed", 10)
    p.tap("OK", exact=True)


def restore_wallet(p, words):
    tab(p, "More")
    top(p)
    seek(p, "Bring back a wallet")
    field = seek(p, "Recovery words", exact=True)
    p.tap_xy(*field.center)
    time.sleep(0.6)
    type_words(p, words)
    button = seek(p, "Store the wallet")
    p.tap_xy(*button.center)
    fingerprint(p)


def receive_address(p):
    tab(p, "Receive")
    found = [t for t in p.texts() if re.match(r"^r?north1[a-z0-9]{20,}$", t)]
    if not found:
        raise RuntimeError("no address on the Receive tab: %s" % p.texts()[:20])
    return found[0]


# ---------------------------------------------------------------------------------------------- 0.4

def remove_wallet_04(p):
    """From 0.4 on, removing the wallet asks for the owner first."""
    tab(p, "More")
    top(p)
    scroll_to(p, "Remove the wallet from this phone")
    p.tap("Remove the wallet from this phone")
    p.wait("Remove the wallet?", 10)
    p.tap("I have the words, remove")
    fingerprint(p)
    p.wait("The wallet was removed", 15)
    p.tap("OK", exact=True)


def pick_file(p, name):
    """In the phone's own file picker: opens Downloads if the file is not on the first page, then picks it."""
    time.sleep(2)
    for attempt in range(3):
        found = p.find(name, exact=True)
        if found:
            p.tap_xy(*found[0].center)
            return
        roots = p.find("Show roots")
        if roots:
            p.tap_xy(*roots[0].center)
            time.sleep(1.5)
        downloads = [n for n in p.find("Downloads", exact=True)]
        if downloads:
            p.tap_xy(*downloads[-1].center)
            time.sleep(2)
    raise RuntimeError("the file %s is not offered by the picker: %s" % (name, p.texts()[:30]))


def import_setup(p, host_path, name="polarka-setup.json"):
    """Brings a setup file into the phone the way a user does: the file is in Downloads and is chosen in the app."""
    p.adb("push", host_path, "/sdcard/Download/" + name)
    time.sleep(1)
    scroll_to(p, "Choose the setup file")
    p.tap("Choose the setup file")
    pick_file(p, name)
    # the page keeps its scroll position, so the title of the block may be just above what is visible
    end = time.time() + 20
    while time.time() < end and not (p.has("Check before saving") or p.has("Save and connect")):
        time.sleep(0.5)
    if not (p.has("Check before saving") or p.has("Save and connect")):
        raise RuntimeError("the setup that was chosen is not offered for saving; on screen: %s" % p.texts()[:40])


def unlock_if_locked(p):
    time.sleep(1.5)
    if p.has("The wallet is locked") or p.has("Confirm that it is you"):
        if not p.has("Confirm that it is you"):
            p.tap("Unlock", exact=True)
        fingerprint(p)


def send(p, to, amount, everything=False):
    """Fills the Send form, checks the payment and confirms it with the fingerprint. Returns the id shown."""
    tab(p, "Send")
    top(p)
    p.tap("Recipient address")
    p.type(to)
    if everything:
        p.back()
        p.tap("Send everything")
    else:
        p.tap("Amount (NORTH)")
        p.type(amount)
        p.back()
    time.sleep(0.6)
    scroll_to(p, "Check the payment")
    p.tap("Check the payment")
    p.wait("Confirm the payment", 30)
    shown = [t for t in p.texts() if re.match(r"^r?north1[a-z0-9]{20,}$", t)]
    if to not in shown:
        raise RuntimeError("the confirmation does not show the recipient: %s" % shown)
    scroll_to(p, "Sign with my fingerprint and send")
    p.tap("Sign with my fingerprint and send")
    fingerprint(p)
    p.wait("The node accepted the transaction", 40)
    txid = [t for t in p.texts() if re.match(r"^[0-9a-f]{64}$", t)][0]
    p.tap("OK", exact=True)
    return txid


def type_words(p, words):
    """Types recovery words one by one. A long text sent at once loses characters on the way into the phone."""
    for i, word in enumerate(words):
        p.sh("input text '%s'" % word)
        if i + 1 < len(words):
            p.sh("input keyevent 62")
    time.sleep(0.5)


def hide_keyboard(p):
    """Closes the on-screen keyboard if it is open. (A blind "back" would leave the app when it is not.)"""
    if "mInputShown=true" in p.sh("dumpsys input_method | grep -m1 mInputShown"):
        p.back()
        time.sleep(0.8)


def seek(p, text, exact=False, up=7, down=14):
    """Finds a text on a page that scrolls: first downwards from where the page stands, then back up to the top."""
    hide_keyboard(p)
    for _ in range(up):
        found = p.find(text, exact, nodes=visible(p.nodes()))
        if found:
            return found[0]
        p.swipe_up()
    for _ in range(down):
        found = p.find(text, exact, nodes=visible(p.nodes()))
        if found:
            return found[0]
        p.sh("input swipe 540 900 540 1500 250")
        time.sleep(0.6)
    raise RuntimeError("%r is nowhere on the page; the screen shows: %s" % (text, p.texts()[:30]))
