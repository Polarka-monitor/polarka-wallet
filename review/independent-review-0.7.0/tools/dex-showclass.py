#!/usr/bin/env python3
"""dex-showclass.py A.dump B.dump CLASS [N]: the first N lines of a unified diff of one class's normalised text."""
import difflib, importlib.util, os, sys

spec = importlib.util.spec_from_file_location("cd", os.path.join(os.path.dirname(os.path.abspath(__file__)), "dex-classdiff-lib.py"))
lib = importlib.util.module_from_spec(spec)
spec.loader.exec_module(lib)
a, b = lib.classes(sys.argv[1]), lib.classes(sys.argv[2])
n = int(sys.argv[4]) if len(sys.argv) > 4 else 60
d = list(difflib.unified_diff(a.get(sys.argv[3], "").split("\n"), b.get(sys.argv[3], "").split("\n"), lineterm="", n=1))
print("\n".join(d[:n]))
print("diff lines:", len(d))
