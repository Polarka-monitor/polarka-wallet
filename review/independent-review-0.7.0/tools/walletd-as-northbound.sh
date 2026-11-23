#!/bin/sh
# Starts the reviewer's copy of walletd.py (polarka-agent at 505a3de, file sha256 934709aa...) the way the wallet's
# test support starts a real Northbound server (ServerSupport.startReal: settings in TRUENORTH_* variables).
# Use: NORTHBOUND_SERVER=this-file [NORTHBOUND_NO_WAIT=1] sh tools/gradle-bg.sh NAME :app:test...UnitTest --tests ...
here="$(cd "$(dirname "$0")" && pwd)"
exec /opt/homebrew/bin/python3 -I -B "$here/walletd-copy/walletd.py" --chain "$TRUENORTH_NETWORK" \
    --rpc-url "$TRUENORTH_RPC_URL" --cookie "$TRUENORTH_RPC_COOKIE_PATH" --listen "$TRUENORTH_SERVER_BIND"
