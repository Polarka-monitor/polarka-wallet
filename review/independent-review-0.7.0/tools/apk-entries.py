#!/usr/bin/env python3
"""apk-entries.py A.apk B.apk: the entries of two packages that differ (by CRC and size), and those only in one."""
import sys, zipfile

def entries(p):
    with zipfile.ZipFile(p) as z:
        return {i.filename: (i.CRC, i.file_size) for i in z.infolist()}

a, b = entries(sys.argv[1]), entries(sys.argv[2])
for name in sorted(set(a) | set(b)):
    if name not in a:
        print("only in B:", name)
    elif name not in b:
        print("only in A:", name)
    elif a[name] != b[name]:
        print("differs:  ", name, a[name][1], b[name][1])
print("entries:", len(a), len(b))
