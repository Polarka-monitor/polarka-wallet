#!/usr/bin/env python3
# Retest: tries shapes of calls on the gateway's own rules (Rules.check and Gateway.handle) without any node.
# Nothing is written next to gateway.py (no byte code), nothing is sent anywhere.
import sys, json, importlib.util, argparse
sys.dont_write_bytecode = True
path = sys.argv[1]
spec = importlib.util.spec_from_file_location("gateway_under_test", path)
gw = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gw)

A = "rnorth1zfgvaxg6parqfv2drvsk9zadefh40zg97ese44hjkjzkjamkftpzsnpx4h6"
D = "addr(%s)" % A
H = "ab" * 32
TX = "02" * 120
rules = gw.Rules("rnorth")

def verdict(method, params):
    try:
        rules.check(method, params)
        return "PASS"
    except gw.Refused:
        return "refused"
    except Exception as e:
        return "EXCEPTION " + type(e).__name__

must_pass = [
    ("getblockcount", []), ("getblockchaininfo", []), ("getblockhash", [5]), ("getblockheader", [H]), ("getblock", [H, 2]),
    ("scantxoutset", ["start", [D]]), ("scantxoutset", ["start", [D] * 250]), ("scanblocks", ["start", [D], 0]),
    ("getnetworkhashps", [120]), ("testmempoolaccept", [[TX]]), ("sendrawtransaction", [TX]),
    ("getrawtransaction", [H, 1]), ("gettxout", [H, 0, True]), ("getmempoolentry", [H]),
]
must_refuse = [
    ("stop", []), ("getpeerinfo", []), ("dumptxoutset", ["/tmp/x"]), ("generatetoaddress", [1, A]), ("setban", ["1.2.3.4", "add"]),
    ("importdescriptors", [[]]), ("invalidateblock", [H]), ("pruneblockchain", [1]), ("savemempool", []), ("getblocktemplate", []),
    ("submitblock", [TX]), ("addnode", ["x", "add"]), ("logging", []), ("help", []), ("getrpcinfo", []), ("echo", ["x"]),
    ("scantxoutset ", ["start", [D]]), ("Stop", []), ("", []), ("getmempoolinfo", []), ("getrawmempool", []), ("gettxoutsetinfo", []),
    ("scantxoutset", ["abort"]), ("scantxoutset", ["status"]), ("scantxoutset", ["start", [{"desc": D, "range": 1000}]]),
    ("scantxoutset", ["start", ["qrh(tpubD6NzVbkrYhZ4X/0/*)"]]), ("scantxoutset", ["start", ["raw(00)"]]), ("scantxoutset", ["start", [D, 5]]),
    ("scantxoutset", ["start", []]), ("scantxoutset", ["start", [D] * 251]), ("scantxoutset", ["start", D]),
    ("scantxoutset", ["start", [D], {"x": 1}]), ("scantxoutset", ["START", [D]]), ("scantxoutset", ["start", [D + "\n"]]),
    ("scantxoutset", ["start", [D + ")"]]), ("scantxoutset", ["start", [D + "," + D]]), ("scantxoutset", ["start", ["combo(%s)" % A]]),
    ("scantxoutset", ["start", ["addr(%s)#" % A]]), ("scantxoutset", ["start", [" " + D]]), ("scantxoutset", ["start", ["addr(" + "q" * 500 + ")"]]),
    ("scanblocks", ["abort"]), ("scanblocks", ["start", [D], 0, 10, "basic"]), ("scanblocks", ["start", [D], 0, 10, "basic", {"filter_false_positives": True}]),
    ("scanblocks", ["start", [D], -1]), ("scanblocks", ["start", [D], 0.0]), ("scanblocks", ["start", [D], True]), ("scanblocks", ["start", [D]]),
    ("getblock", [H, 3]), ("getblock", [H, True]), ("getblock", [H, 2, 1]), ("getblock", [H.upper()]), ("getblock", [H + "0"]), ("getblock", ["latest"]),
    ("getblock", [H, 1.0]), ("getblockheader", [H, 1]),
    ("sendrawtransaction", [TX, 0]), ("sendrawtransaction", [TX, "0"]), ("sendrawtransaction", [TX[:-1]]), ("sendrawtransaction", ["AB" * 120]),
    ("sendrawtransaction", [""]), ("sendrawtransaction", ["ab" * 400001]), ("sendrawtransaction", [[TX]]), ("sendrawtransaction", []),
    ("testmempoolaccept", [[TX, TX]]), ("testmempoolaccept", [[TX], 0]), ("testmempoolaccept", [TX]), ("testmempoolaccept", [[]]),
    ("getrawtransaction", [H, 2]), ("getrawtransaction", [H, 1, "x"]), ("getrawtransaction", [H, True, H, 1]),
    ("gettxout", [H, -1]), ("gettxout", [H, 100001]), ("gettxout", [H, 0, 1]), ("gettxout", [H, 0, True, 1]), ("gettxout", [H]),
    ("getblockhash", [-1]), ("getblockhash", [1000000000]), ("getblockhash", [True]), ("getblockhash", ["5"]), ("getblockhash", [5, 5]),
    ("getnetworkhashps", [0]), ("getnetworkhashps", [100001]), ("getnetworkhashps", [120, -2]), ("estimatesmartfee", [1, "ECONOMICAL"]),
    ("getmempoolentry", [H, True]), ("getmempoolentry", ["x"]),
]
bad = 0
for m, p in must_pass:
    v = verdict(m, p)
    if v != "PASS":
        bad += 1
        print("UNEXPECTED: the wallet's own call is %s: %s %s" % (v, m, json.dumps(p)[:90]))
for m, p in must_refuse:
    v = verdict(m, p)
    if v != "refused":
        bad += 1
        print("UNEXPECTED: %s: %s %s" % (v, m, json.dumps(p)[:90]))
print("rules: %d shapes that must pass, %d that must be refused, %d unexpected" % (len(must_pass), len(must_refuse), bad))

# the door itself, for requests that never reach the rules or the node
args = argparse.Namespace(hrp="rnorth", upstream="127.0.0.1:1", auth_file="/nonexistent", behind_proxy=False, burst=1000, per_second=1000.0, scan_burst=1000, scans_per_second=1000.0)
door = gw.Gateway(args)
raws = {
    "a batch": json.dumps([{"method": "getblockcount", "params": []}, {"method": "stop", "params": []}]),
    "named parameters": json.dumps({"method": "getblock", "params": {"blockhash": H, "verbosity": 2}}),
    "method is a list": json.dumps({"method": ["stop"], "params": []}),
    "no method": json.dumps({"params": []}),
    "not JSON": "stop",
    "a number": "5",
    "duplicate method keys": '{"method": "getblockcount", "method": "stop", "params": []}',
    "deep nesting": "[" * 200000,
}
odd = 0
for name, raw in raws.items():
    try:
        door.handle("1.2.3.4", raw.encode())
        out = "PASSED ON"
    except gw.Refused as e:
        out = "refused (%d)" % e.status
    except Exception as e:
        out = "exception %s (the handler answers 502 for it)" % type(e).__name__
    if not out.startswith("refused"):
        odd += 1
    print("door: %-22s -> %s" % (name, out))
print("door: %d of %d not refused cleanly" % (odd, len(raws)))
