#!/usr/bin/env python3
"""Starts the wallet fresh and runs its self-test. Usage: t01_selftest.py emulator-5560 [package]"""
import sys
import time
from emu import Phone

serial = sys.argv[1]
package = sys.argv[2] if len(sys.argv) > 2 else "io.github.severka_monitor.northwatch"
p = Phone(serial)
print("Android", p.release())
p.stop(package)
print(p.start(package + "/" + ".MainActivity" if "/" not in package else package).strip().splitlines()[-1])
p.wait("Self-test", 20)
p.tap("Run the self-test")
time.sleep(3)
p.show("after the self-test")
