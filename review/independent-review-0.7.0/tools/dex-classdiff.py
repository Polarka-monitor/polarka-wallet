#!/usr/bin/env python3
"""dex-classdiff.py A.dump B.dump [CLASS-PREFIX]: classes whose normalised dexdump -d text differs between two dumps."""
import importlib.util, os, sys

spec = importlib.util.spec_from_file_location("cd", os.path.join(os.path.dirname(os.path.abspath(__file__)), "dex-classdiff-lib.py"))
lib = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lib)
a, b = lib.classes(sys.argv[1]), lib.classes(sys.argv[2])
prefix = sys.argv[3] if len(sys.argv) > 3 else ""
for n in sorted(set(a) | set(b)):
    if not n.startswith(prefix):
        continue
    if n not in a:
        print("only in B:", n)
    elif n not in b:
        print("only in A:", n)
    elif a[n] != b[n]:
        print("differs:  ", n)
print("classes:", len(a), len(b))
