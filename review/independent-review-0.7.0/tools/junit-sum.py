#!/usr/bin/env python3
"""junit-sum.py FOLDER [CLASS ...]: the totals of the JUnit XML files in one results folder (one edition), and
the tests of the named classes (full names), one a line, with the first 1,500 characters of what a failed one said."""
import glob, os, re, sys

folder, wanted = sys.argv[1], sys.argv[2:]
total = [0, 0, 0, 0]
for p in sorted(glob.glob(os.path.join(folder, "TEST-*.xml"))):
    t = open(p, encoding="utf-8", errors="replace").read()
    head = re.search(r'<testsuite name="([^"]+)" tests="(\d+)" skipped="(\d+)" failures="(\d+)" errors="(\d+)"', t)
    if not head:
        continue
    for i, g in enumerate((2, 4, 5, 3)):
        total[i] += int(head.group(g))
    if int(head.group(3)) or int(head.group(4)) or int(head.group(5)):
        print("%-60s tests=%s failures=%s errors=%s skipped=%s" % (head.group(1), head.group(2), head.group(4), head.group(5), head.group(3)))
    if head.group(1) in wanted:
        for c in re.finditer(r'<testcase name="([^"]+)" classname="[^"]+" time="([^"]+)"(/>|>(.*?)</testcase>)', t, re.S):
            body = c.group(4) or ""
            bad = "<failure" in body or "<error" in body
            skip = "<skipped" in body
            print(("FAIL " if bad else "skip " if skip else "ok   ") + c.group(1), c.group(2))
            if bad:
                print(body[:1500])
print("TOTAL tests=%d failures=%d errors=%d skipped=%d" % tuple(total))
