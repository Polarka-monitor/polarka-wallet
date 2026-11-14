#!/usr/bin/env python3
"""Drives an app on an Android emulator through adb.

The wallet forbids screenshots (FLAG_SECURE), so the screen is read as text from the accessibility tree
(uiautomator dump) and touched by coordinates taken from that tree. Nothing here knows about the wallet.
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

SDK = os.path.expanduser("~/north-build/android-sdk")
ADB = os.path.join(SDK, "platform-tools", "adb")


class Node:
    def __init__(self, el):
        a = el.attrib
        self.text = a.get("text", "")
        self.desc = a.get("content-desc", "")
        self.cls = a.get("class", "")
        self.res = a.get("resource-id", "")
        self.clickable = a.get("clickable") == "true"
        self.enabled = a.get("enabled") == "true"
        self.checked = a.get("checked") == "true"
        self.focused = a.get("focused") == "true"
        self.password = a.get("password") == "true"
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", a.get("bounds", ""))
        self.box = tuple(int(x) for x in m.groups()) if m else (0, 0, 0, 0)

    @property
    def center(self):
        x1, y1, x2, y2 = self.box
        return ((x1 + x2) // 2, (y1 + y2) // 2)

    @property
    def label(self):
        return self.text or self.desc

    def __repr__(self):
        return "<%s %r %s>" % (self.cls.split(".")[-1], self.label, self.box)


class Phone:
    def __init__(self, serial):
        self.serial = serial

    def adb(self, *args, timeout=60, binary=False, stdin=None):
        r = subprocess.run([ADB, "-s", self.serial, *args], capture_output=True, timeout=timeout, input=stdin)
        return r.stdout if binary else r.stdout.decode("utf-8", "replace")

    def sh(self, command, timeout=60):
        return self.adb("shell", command, timeout=timeout)

    def emu(self, *args):
        return self.adb("emu", *args)

    def nodes(self):
        for attempt in range(6):
            out = self.adb("exec-out", "uiautomator", "dump", "/dev/tty", timeout=40)
            start = out.find("<?xml")
            end = out.rfind("</hierarchy>")
            if start >= 0 and end > start:
                try:
                    root = ET.fromstring(out[start:end + len("</hierarchy>")])
                    return [Node(el) for el in root.iter("node")]
                except ET.ParseError:
                    pass
            time.sleep(0.7)
        raise RuntimeError("the screen could not be read")

    def texts(self):
        return [n.label for n in self.nodes() if n.label]

    def show(self, title=""):
        print("--- screen %s" % title)
        for n in self.nodes():
            if n.label or n.cls.endswith("EditText"):
                flags = "".join([
                    "C" if n.clickable else "-", "E" if n.enabled else "-", "X" if n.checked else "-",
                    "P" if n.password else "-"])
                print("  %s %-14s %s %s" % (flags, n.cls.split(".")[-1][:14], n.box, n.label[:160].replace("\n", " / ")))
        print("---")
        sys.stdout.flush()

    def find(self, text, exact=False, nodes=None):
        found = []
        for n in nodes if nodes is not None else self.nodes():
            for value in (n.text, n.desc):
                if value and ((value.lower() == text.lower()) if exact else (text.lower() in value.lower())):
                    found.append(n)
                    break
        return found

    def has(self, text, exact=False):
        return bool(self.find(text, exact))

    def wait(self, text, seconds=20, exact=False, gone=False):
        end = time.time() + seconds
        while time.time() < end:
            if self.has(text, exact) != gone:
                return True
            time.sleep(0.6)
        raise RuntimeError("%r did not %s within %d s; on screen: %s" % (
            text, "go away" if gone else "appear", seconds, self.texts()[:40]))

    def tap_xy(self, x, y):
        self.sh("input tap %d %d" % (x, y))
        time.sleep(0.5)

    def tap(self, text, exact=False, index=0, wait=10):
        end = time.time() + wait
        while True:
            found = self.find(text, exact)
            if len(found) > index:
                break
            if time.time() > end:
                raise RuntimeError("nothing to tap for %r; on screen: %s" % (text, self.texts()[:40]))
            time.sleep(0.6)
        self.tap_xy(*found[index].center)
        return found[index]

    def type(self, text):
        # "input text" takes %s for a space; other shell characters are quoted.
        safe = text.replace("\\", "\\\\").replace(" ", "%s").replace("'", "\\'").replace('"', '\\"')
        for ch in "&|;<>()$`":
            safe = safe.replace(ch, "\\" + ch)
        self.sh("input text '%s'" % safe)
        time.sleep(0.4)

    def key(self, name):
        self.sh("input keyevent %s" % name)
        time.sleep(0.4)

    def back(self):
        self.key("KEYCODE_BACK")

    def swipe_up(self, times=1):
        for _ in range(times):
            self.sh("input swipe 540 1700 540 700 250")
            time.sleep(0.6)

    def swipe_down(self, times=1):
        for _ in range(times):
            self.sh("input swipe 540 700 540 1700 250")
            time.sleep(0.6)

    def start(self, component):
        return self.sh("am start -W -n %s" % component)

    def stop(self, package):
        return self.sh("am force-stop %s" % package)

    def finger(self, number=1):
        self.emu("finger", "touch", str(number))
        time.sleep(0.5)

    def release(self):
        return self.sh("getprop ro.build.version.release").strip()


if __name__ == "__main__":
    p = Phone(sys.argv[1] if len(sys.argv) > 1 else "emulator-5560")
    p.show()
