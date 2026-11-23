#!/usr/bin/env python3
"""junit-show.py CLASS [CLASS ...]: the tests of a class from the last run in the reviewer's clone, one a line,
with what a failed one said. With no class: every class, one line each."""
import glob, os, re, sys
ROOT = os.path.expanduser("~/north-build/audit-0.7.0/polarka-wallet")
wanted = sys.argv[1:]
paths = sorted(glob.glob(ROOT + "/*/build/test-results/*/TEST-*.xml"))
total = [0, 0, 0, 0]
for p in paths:
    t = open(p, encoding="utf-8", errors="replace").read()
    head = re.search(r'<testsuite name="([^"]+)" tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)"', t)
    if not head:
        continue
    if not wanted:
        print("%-60s tests=%s failures=%s errors=%s skipped=%s" % (head.group(1), head.group(2), head.group(4), head.group(5), head.group(3)))
        for i, g in enumerate((2, 4, 5, 3)):
            total[i] += int(head.group(g))
        continue
    if head.group(1) not in wanted:
        continue
    print(head.group(0))
    for c in re.finditer(r'<testcase name="([^"]+)" classname="[^"]+" time="([^"]+)"(/>|>(.*?)</testcase>)', t, re.S):
        bad = c.group(4) and ("<failure" in c.group(4) or "<error" in c.group(4))
        print(("FAIL " if bad else "ok   ") + c.group(1), c.group(2))
        if bad:
            print(c.group(4)[:2500])
if not wanted:
    print("TOTAL tests=%d failures=%d errors=%d skipped=%d" % tuple(total))
