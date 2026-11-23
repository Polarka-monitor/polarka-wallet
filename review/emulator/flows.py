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


def scroll_to(p, text, tries=16, exact=False):
    hide_keyboard(p)            # a swipe that begins on an open keyboard moves nothing
    for _ in range(tries):
        found = p.find(text, exact, nodes=visible(p.nodes()))
        if found:
            return found[0]
        # slowly and without a fling: a fast swipe can carry a button past the screen between two looks (run72)
        p.sh("input swipe 540 1500 540 900 900")
        time.sleep(0.6)
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


def make_wallet(p, count=24):
    """Makes a new wallet from the overview. Returns its recovery words. Since 0.6 the owner chooses 24 words
    (what is set) or 12; before that there was no choice."""
    if count != 24:
        chip = seek(p, "%d words" % count, exact=True)
        p.tap_xy(*chip.center)
        time.sleep(0.5)
    scroll_to(p, "Make a new wallet")
    p.tap("Make a new wallet")
    p.wait("Your recovery words", 15)
    words = read_words(p)
    if len(words) != count:
        raise RuntimeError("%d words were asked for and %d are shown" % (count, len(words)))
    if not p.has("Write these %d words on paper" % count):
        raise RuntimeError("the screen does not say how many words there are to write: %s" % p.texts()[:12])
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
    top(p)                      # the notice is at the top of the page, which stays where it was (run72)
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


def answer(p, text, seconds=20):
    """Waits for what the app says after a button was pressed. The answer stands above the button, and when the
    field above it became shorter at the same moment (words that match are taken out of it), the answer can lie
    just above the part of the page that is on the screen. So between the looks the page is moved a little down
    and back."""
    moves = (None, "input swipe 540 1000 540 1400 250", "input swipe 540 1400 540 1000 250")
    end, i = time.time() + seconds, 0
    while True:
        if p.has(text):
            return
        if time.time() > end:
            raise RuntimeError("%r was not said within %d s; on screen: %s" % (text, seconds, p.texts()[:40]))
        if moves[i % 3]:
            p.sh(moves[i % 3])
        i += 1
        time.sleep(0.8)


def field_of(p, title, label="Recovery words"):
    """The field with that label in the block with that title: the first one below the title. More has two
    fields for recovery words since 0.5.1 (the check of the whole paper, and the paper address)."""
    seek(p, title)
    for _ in range(5):
        nodes = visible(p.nodes())
        head = p.find(title, nodes=nodes)
        below = [n for n in p.find(label, True, nodes=nodes) if head and n.center[1] > head[0].center[1]]
        if below:
            return min(below, key=lambda n: n.center[1])
        p.sh("input swipe 540 1500 540 1200 250")       # a little further: the title stays, the field comes up
        time.sleep(0.6)
    raise RuntimeError("no field %r under %r; the screen shows: %s" % (label, title, p.texts()[:30]))


def check_paper(p, words):
    """More, "Check the whole paper": types the words into that block and presses its button (since 0.5.1)."""
    tab(p, "More")
    top(p)
    field = field_of(p, "Check the whole paper")
    p.tap_xy(*field.center)
    time.sleep(0.6)
    type_words(p, words)
    button = seek(p, "Check the words", exact=True)
    p.tap_xy(*button.center)
    time.sleep(1.0)


def restore_wallet_with_a_slip(p, words):
    """Brings a wallet back, with one letter too many in the last word at first (since 0.5.1): the app has to
    name that word and its place, ask for no fingerprint, and keep what was typed; one letter is then deleted."""
    tab(p, "More")
    top(p)
    seek(p, "Bring back a wallet")
    field = seek(p, "Recovery words", exact=True)
    p.tap_xy(*field.center)
    time.sleep(0.6)
    type_words(p, words[:-1] + [words[-1] + "x"])
    button = seek(p, "Store the wallet")
    p.tap_xy(*button.center)
    said = "Not on the list of recovery words: %d (%sx)" % (len(words), words[-1])
    answer(p, said, 20)
    if p.has("Confirm that it is you"):
        raise RuntimeError("the fingerprint is asked for although the words are not valid")
    seek(p, " ".join(words) + "x", exact=True)          # what was typed is still in the field
    # The field was not touched again, so it still has the cursor. Control and End take it behind the last letter
    # (End alone would stop at the end of the line it stands in), and the one letter too many is deleted.
    p.sh("input keycombination 113 123")
    p.sh("input keyevent 67")
    time.sleep(0.6)
    seek(p, " ".join(words), exact=True)                # the words as they are on the paper
    button = seek(p, "Store the wallet")
    p.tap_xy(*button.center)
    fingerprint(p)
    return said


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
    top(p)                      # the notice is at the top of the page, which stays where it was (run72)
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
    wait_any(p, ("The node accepted the transaction", "The server took the transaction"), 60)
    txid = [t for t in p.texts() if re.match(r"^[0-9a-f]{64}$", t)][0]
    p.tap("OK", exact=True)
    return txid


def wait_any(p, texts, seconds=20):
    """Waits until one of some texts is on the screen and returns that one."""
    end = time.time() + seconds
    while True:
        shown = p.texts()
        for text in texts:
            if any(text.lower() in t.lower() for t in shown):
                return text
        if time.time() > end:
            raise RuntimeError("none of %s appeared within %d s; on screen: %s" % (list(texts), seconds, shown[:40]))
        time.sleep(0.6)


FIRST_SCREENS = ("Connect through a public server", "Connect to a public node", "Connect to a node of your own")


def first_screen(p, seconds=30):
    """Waits for the screen of an app that has no setup yet, whatever the version: 0.6 opens with a wallet
    server, 0.5 with a public node, and every version offers a node of one's own."""
    return wait_any(p, FIRST_SCREENS, seconds)


def asking_gone(p, seconds=30):
    """Waits until the app has its answer, from a node or from a wallet server."""
    end = time.time() + seconds
    while any(t.startswith("Asking the ") for t in p.texts()):
        if time.time() > end:
            raise RuntimeError("the app is still asking after %d s; on screen: %s" % (seconds, p.texts()[:20]))
        time.sleep(0.6)


def connect_to_server(p, url, filled="api.northboundwallet.org"):
    """On the first screen (since 0.6): writes another server address over the one that is filled in, and goes
    on to the check before saving."""
    top(p)
    field = seek(p, filled)
    p.tap_xy(*field.center)
    time.sleep(0.6)
    p.sh("input keycombination 113 29")         # Control and A: everything in the field
    p.sh("input keyevent 67")
    time.sleep(0.6)
    if p.has(filled):
        # this Android did not take the two keys together: to the end of the field, and back letter by letter
        p.sh("input keycombination 113 123")
        p.sh("input keyevent " + " ".join(["67"] * 60))
        time.sleep(0.6)
    if p.has(filled):
        raise RuntimeError("the address that was filled in could not be cleared; on screen: %s" % p.texts()[:20])
    p.type(url)
    if not p.find(url, exact=True):
        raise RuntimeError("the field does not hold %s; on screen: %s" % (url, p.texts()[:20]))
    hide_keyboard(p)
    button = seek(p, "Continue", exact=True)
    p.tap_xy(*button.center)
    wait_any(p, ("Check before saving",), 20)


def remove_setup(p, wallet):
    """More, "Remove the setup": the app forgets the node or the server. With a wallet in the phone its owner is
    asked for first. Afterwards the first screen is there again; the wallet, if there is one, stays."""
    tab(p, "More")
    top(p)
    button = seek(p, "Remove the setup", exact=True)
    p.tap_xy(*button.center)
    p.wait("Remove the setup?", 10)
    p.tap("Remove", exact=True)
    if wallet:
        fingerprint(p)
    first_screen(p, 20)


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
