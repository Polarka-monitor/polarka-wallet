#!/usr/bin/env python3
# crosscheck.py: words to addresses, checked against two implementations that share no code with the wallet.
#   1. words -> public descriptor: the cold address tool (pure Python, standard library only)
#   2. public descriptor -> addresses: TrueNorth Core itself (deriveaddresses), on an offline mainnet node
# Input: the file written by PlannerPropertyTest.writesRandomPhrasesForTheCrossCheck (tab separated):
#   phrase, address 0/0, address 0/19, address 1/3, receive descriptor, all as the wallet's Kotlin code computed them.
# Usage: crosscheck.py PHRASES.tsv COLD_TOOL_DIR TN_BIN_DIR
import hashlib, importlib.util, json, os, shutil, subprocess, sys, tempfile, time

phrases, tool, bindir = sys.argv[1], sys.argv[2], sys.argv[3]

def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

tn = load("tnseed", os.path.join(tool, "tn-seed.py"))
ca = load("coldaddress", os.path.join(tool, "cold-address.py"))

data = tempfile.mkdtemp(prefix="north-crosscheck-")
port = "29554"
node = subprocess.Popen([os.path.join(bindir, "truenorthd"), "-datadir=" + data, "-listen=0", "-connect=0", "-dnsseed=0", "-fixedseeds=0",
                         "-disablewallet=1", "-server=1", "-rpcbind=127.0.0.1", "-rpcallowip=127.0.0.1", "-rpcport=" + port, "-printtoconsole=0"],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)

def cli(*args):
    r = subprocess.run([os.path.join(bindir, "truenorth-cli"), "-datadir=" + data, "-rpcport=" + port] + list(args), capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(r.stderr.strip())
    return r.stdout.strip()

try:
    for _ in range(200):
        try:
            cli("getblockcount")
            break
        except Exception:
            time.sleep(0.3)
    total = bad = 0
    for line in open(phrases, encoding="utf-8"):
        phrase, a00, a019, a13, descriptor = line.rstrip("\n").split("\t")
        total += 1
        seed = hashlib.pbkdf2_hmac("sha512", phrase.encode(), b"mnemonic", 2048)                 # BIP39, no passphrase
        python_descriptor = ca.public_descriptor(tn, seed, "main")
        receive = json.loads(cli("deriveaddresses", descriptor, "[0,19]"))
        change_body = descriptor.split("#")[0].replace("/0/*)", "/1/*)")
        change = json.loads(cli("deriveaddresses", change_body + "#" + json.loads(cli("getdescriptorinfo", change_body))["checksum"], "[3,3]"))
        ok = python_descriptor == descriptor and receive[0] == a00 and receive[19] == a019 and change[0] == a13
        if not ok:
            bad += 1
            print("DIFFERENT for phrase number", total)
    print("CROSSCHECK %s: %d phrases, %d different" % ("OK" if bad == 0 and total > 0 else "FAILED", total, bad))
    sys.exit(0 if bad == 0 and total > 0 else 1)
finally:
    try:
        cli("stop")
        node.wait(timeout=40)
    except Exception:
        node.kill()
    shutil.rmtree(data, ignore_errors=True)
