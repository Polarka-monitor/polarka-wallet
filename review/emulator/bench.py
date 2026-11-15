#!/usr/bin/env python3
"""A private test network for the wallet on emulators: one regtest node, the public gateway, a stand-in for a
wallet server, and TLS in front of all three.

  bench.py start            start everything and keep running (use nohup); writes run/current.json
  bench.py mine N           mine N blocks to an address nobody holds
  bench.py reward ADDRESS   mine one block whose reward goes to ADDRESS
  bench.py pay ADDRESS AMT  an ordinary payment from the bench wallet (stays unconfirmed until "mine")
  bench.py call METHOD ...  any node call, as the bench itself
  bench.py setup KIND [ADDRESS ...]   writes a setup file for the wallet (own or server) and prints its path
  bench.py standin JSON     sets the test switches of the stand-in server, for example '{"stale": true}'
  bench.py seen             the connections each TLS port has taken and the requests the stand-in has answered
  bench.py info             what runs where

An emulator reaches the host as 10.0.2.2, so the wallet is set up with
  own node:      https://10.0.2.2:18651  (straight to the node, user "phone", limited like a real phone account)
  public node:   https://10.0.2.2:18652  (through gateway.py, no account)
  wallet server: https://10.0.2.2:18654  (the stand-in for a Northbound server, review/standin; since 0.6)
Port 18653 is left to the gate of t04_not_gone_through.py.
"""
import hashlib
import hmac
import json
import os
import signal
import socket
import subprocess
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
NAME = os.environ.get("BENCH", "")              # a second and a third bench for emulators that run side by side: BENCH=2, BENCH=3
RUN = os.path.join(HERE, "run" + NAME)
BIN = os.environ.get("TN_BIN") or sys.exit("set TN_BIN to the folder with the official truenorthd and truenorth-cli")
GATEWAY = os.environ.get("POLARKA_GATEWAY") or os.path.expanduser("~/north-build/polarka-node/gateway.py")
# the stand-in for a wallet server: beside this folder in the sources, or where the sources lie on the machine this was written on
STANDIN = os.environ.get("POLARKA_STANDIN") or next(
    (p for p in (os.path.join(HERE, "..", "standin", "northbound_standin.py"),) if os.path.exists(p)),
    os.path.expanduser("~/north-build/north-wallet/review/standin/northbound_standin.py"))
TLS = os.path.join(HERE, "tls")
OWN_PORT, PUBLIC_PORT = (18651, 18652) if not NAME else (18651 + 10 * int(NAME), 18652 + 10 * int(NAME))
SERVER_PORT = OWN_PORT + 3
PHONE_CALLS = ("getblockcount,getblockchaininfo,getbestblockhash,getblockhash,getblockheader,scantxoutset,estimatesmartfee,"
               "testmempoolaccept,sendrawtransaction,getrawtransaction,gettxout,getmempoolentry,scanblocks,getblock,getnetworkhashps")


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    return port


def rpcauth(user, password):
    salt = os.urandom(16).hex()
    return "%s:%s$%s" % (user, salt, hmac.new(salt.encode(), password.encode(), hashlib.sha256).hexdigest())


def state():
    with open(os.path.join(RUN, "current.json")) as f:
        return json.load(f)


def cli(st, *args, wallet=None, timeout=600):
    cmd = [os.path.join(BIN, "truenorth-cli"), "-datadir=" + st["node_dir"], "-chain=regtest", "-rpcport=%d" % st["node_port"]]
    if wallet:
        cmd.append("-rpcwallet=" + wallet)
    r = subprocess.run(cmd + [str(a) for a in args], capture_output=True, text=True, timeout=timeout)
    if r.returncode:
        raise SystemExit("node refused %s: %s" % (" ".join(str(a) for a in args[:2]), r.stderr.strip()[:400]))
    try:
        return json.loads(r.stdout)
    except ValueError:
        return r.stdout.strip()


def wait_port(port, what, seconds=20):
    end = time.time() + seconds
    while time.time() < end:
        try:
            socket.create_connection(("127.0.0.1", port), timeout=1).close()
            return
        except OSError:
            time.sleep(0.2)
    raise SystemExit("%s did not start" % what)


def start():
    os.makedirs(RUN, exist_ok=True)
    work = os.path.join(RUN, time.strftime("%Y%m%d-%H%M%S"))
    node_dir = os.path.join(work, "node")
    os.makedirs(node_dir)
    node_port, gateway_port, standin_port = free_port(), free_port(), free_port()
    if len({node_port, gateway_port, standin_port}) != 3:
        raise SystemExit("the free ports are not three different ones; start again")
    phone_password = "ph" + os.urandom(12).hex()
    gateway_password = "gw" + os.urandom(12).hex()
    auth_file = os.path.join(work, "gateway-auth")
    with open(os.open(auth_file, os.O_WRONLY | os.O_CREAT, 0o600), "w") as f:
        f.write("gateway:" + gateway_password + "\n")
    children = []
    children.append(subprocess.Popen(
        [os.path.join(BIN, "truenorthd"), "-datadir=" + node_dir, "-chain=regtest", "-listen=0", "-connect=0", "-dnsseed=0",
         "-fixedseeds=0", "-server=1", "-rpcport=%d" % node_port, "-blockfilterindex=1", "-fallbackfee=0.0001",
         "-rpcauth=" + rpcauth("phone", phone_password), "-rpcwhitelist=phone:" + PHONE_CALLS,
         "-rpcauth=" + rpcauth("gateway", gateway_password), "-rpcwhitelist=gateway:" + PHONE_CALLS,
         "-rpcwhitelistdefault=0", "-printtoconsole=0"],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
    st = {"work": work, "node_dir": node_dir, "node_port": node_port, "gateway_port": gateway_port,
          "own_url": "https://10.0.2.2:%d" % OWN_PORT, "public_url": "https://10.0.2.2:%d" % PUBLIC_PORT,
          "standin_port": standin_port, "server_url": "https://10.0.2.2:%d" % SERVER_PORT, "pid": os.getpid()}
    try:
        for _ in range(120):
            try:
                subprocess.run([os.path.join(BIN, "truenorth-cli"), "-datadir=" + node_dir, "-chain=regtest",
                                "-rpcport=%d" % node_port, "getblockcount"], capture_output=True, check=True, timeout=10)
                break
            except Exception:
                time.sleep(0.5)
        else:
            raise SystemExit("the node did not start")
        cli(st, "createwallet", "bench")
        st["bench_address"] = cli(st, "getnewaddress", wallet="bench")
        cli(st, "generatetoaddress", 120, st["bench_address"])
        log = open(os.path.join(work, "gateway.log"), "w")
        children.append(subprocess.Popen(
            [sys.executable, GATEWAY, "--listen", "127.0.0.1:%d" % gateway_port, "--upstream", "127.0.0.1:%d" % node_port,
             "--auth-file", auth_file, "--hrp", "rnorth"], stdout=log, stderr=subprocess.STDOUT))
        wait_port(gateway_port, "the gateway")
        # The stand-in logs in to the node with the node's own cookie, as a server beside its node would. It
        # reads the chain before it listens, and it writes every request down (bench.py seen counts them).
        standin_log = open(os.path.join(work, "standin.log"), "w")
        children.append(subprocess.Popen(
            [sys.executable, "-B", STANDIN, "--rpc-url", "http://127.0.0.1:%d" % node_port,
             "--cookie", os.path.join(node_dir, "regtest", ".cookie"), "--port", str(standin_port), "--warm", "--verbose"],
            stdout=standin_log, stderr=subprocess.STDOUT))
        wait_port(standin_port, "the stand-in for a wallet server", 90)
        relay_log = open(os.path.join(work, "relay.log"), "w")
        for listen, target in ((OWN_PORT, node_port), (PUBLIC_PORT, gateway_port), (SERVER_PORT, standin_port)):
            children.append(subprocess.Popen(
                [sys.executable, os.path.join(HERE, "tls_relay.py"), str(listen), str(target),
                 os.path.join(TLS, "server.pem"), os.path.join(TLS, "server.key")], stdout=relay_log, stderr=subprocess.STDOUT))
            wait_port(listen, "a relay")
        # The setup file of an own node, as the user would get it from the node kit. The password is a test one.
        with open(os.open(os.path.join(work, "phone-password"), os.O_WRONLY | os.O_CREAT, 0o600), "w") as f:
            f.write(phone_password + "\n")
        with open(os.path.join(RUN, "current.json"), "w") as f:
            json.dump(st, f, indent=1)
        print("bench is up: node %d, gateway %d, stand-in %d, own %d, public %d, server %d, height %s" % (
            node_port, gateway_port, standin_port, OWN_PORT, PUBLIC_PORT, SERVER_PORT, cli(st, "getblockcount")), flush=True)
        signal.signal(signal.SIGTERM, lambda *a: sys.exit(0))
        while children[0].poll() is None:
            time.sleep(1)
    finally:
        for c in reversed(children):
            try:
                c.terminate()
                c.wait(timeout=20)
            except Exception:
                c.kill()
        print("bench stopped", flush=True)


def setup_file(st, kind, addresses):
    """Writes the wallet's setup file for this bench and returns its path. The test password is not printed."""
    with open(os.path.join(st["work"], "phone-password")) as f:
        password = f.read().strip()
    if kind == "own":
        doc = {"format": "north-watch-setup/1", "node": {"url": st["own_url"], "user": "phone", "password": password},
               "chain": "regtest", "addresses": addresses}
    elif kind == "server":
        # the third form names a wallet server by its address alone; the network is said because this one is a test network
        doc = {"format": "polarka-setup/3", "server": {"url": st["server_url"]}, "chain": "regtest"}
        if addresses:
            doc["addresses"] = addresses
    else:
        raise SystemExit("unknown kind of setup")
    path = os.path.join(st["work"], "setup-%s.json" % kind)
    with open(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as f:
        json.dump(doc, f)
    return path


def seen(st):
    """The connections each TLS port has taken since the bench started, and the requests the stand-in has answered."""
    names = {str(OWN_PORT): "own", str(PUBLIC_PORT): "public", str(SERVER_PORT): "server"}
    connections = dict.fromkeys(names.values(), 0)
    with open(os.path.join(st["work"], "relay.log")) as f:
        for line in f:
            words = line.split()
            if len(words) == 2 and words[0] == "connection" and words[1] in names:
                connections[names[words[1]]] += 1
    with open(os.path.join(st["work"], "standin.log")) as f:
        requests = sum(1 for line in f if '"GET /' in line or '"POST /v' in line)
    return {"connections": connections, "server_requests": requests}


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    what = sys.argv[1]
    if what == "start":
        return start()
    st = state()
    if what == "mine":
        cli(st, "generatetoaddress", int(sys.argv[2]), st["bench_address"])
        print("height", cli(st, "getblockcount"))
    elif what == "reward":
        cli(st, "generatetoaddress", 1, sys.argv[2])
        print("height", cli(st, "getblockcount"))
    elif what == "pay":
        print(cli(st, "sendtoaddress", sys.argv[2], sys.argv[3], wallet="bench"))
    elif what == "call":
        wallet = None
        args = sys.argv[2:]
        if args and args[0].startswith("wallet="):
            wallet, args = args[0][7:], args[1:]
        r = cli(st, *args, wallet=wallet)
        print(json.dumps(r, indent=1) if not isinstance(r, str) else r)
    elif what == "setup":
        print(setup_file(st, sys.argv[2], sys.argv[3:]))
    elif what == "standin":
        request = urllib.request.Request("http://127.0.0.1:%d/__standin/set" % st["standin_port"], sys.argv[2].encode(),
                                         {"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=20) as response:
            print(response.read().decode())
    elif what == "seen":
        print(json.dumps(seen(st)))
    elif what == "info":
        print(json.dumps(dict(st, height=cli(st, "getblockcount"),
                              bench_balance=cli(st, "getbalance", wallet="bench")), indent=1))
    elif what == "stop":
        os.kill(st["pid"], signal.SIGTERM)
        print("asked to stop")
    else:
        raise SystemExit(__doc__)


if __name__ == "__main__":
    main()
