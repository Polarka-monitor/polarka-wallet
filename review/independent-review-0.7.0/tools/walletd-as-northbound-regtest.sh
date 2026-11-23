#!/bin/sh
# As walletd-as-northbound.sh, but runs walletd-regtest.py: walletd.py with one line changed (node_ready does not take
# initialblockdownload as "not ready" on regtest, because a fresh regtest node is in IBD until its first block is mined
# and the wallet's test support waits for /ready before any block is mined). Nothing else differs (diff the two files).
here="$(cd "$(dirname "$0")" && pwd)"
exec /opt/homebrew/bin/python3 -I -B "$here/walletd-copy/walletd-regtest.py" --chain "$TRUENORTH_NETWORK" \
    --rpc-url "$TRUENORTH_RPC_URL" --cookie "$TRUENORTH_RPC_COOKIE_PATH" --listen "$TRUENORTH_SERVER_BIND"
