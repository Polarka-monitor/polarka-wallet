# A stand-in for a Northbound wallet server

`northbound_standin.py` answers the requests of the Northbound API, version 1, from a TrueNorth Core node. The
wallet's tests use it in place of the real server (github.com/nova9/northbound). The real server's code is
not built or run anywhere in this repository: the stand-in was written from its sources (tag v0.1.0-rc.5 and
the commits 846e444 and a531965) and then compared with the public server. What the API is and what the
wallet takes for granted about it is in `docs/northbound-api.md`.

It is a test double. It listens on 127.0.0.1 only, keeps no database, answers one request at a time and
trusts its node. It is not meant to serve anybody.

## Running it

    python3 -B northbound_standin.py --rpc-url http://127.0.0.1:PORT --cookie DATADIR/regtest/.cookie

It prints `listening PORT` when it is ready. `--port` names a port (0 takes a free one), `--warm` reads the
whole chain first, `--exit-with-stdin` makes it stop when its standard input closes, so that it never outlives
the test that started it. It needs Python 3.9 or later and nothing beyond the standard library; it has been
run with 3.14.

The node is asked with its cookie, the login a node writes for programs on its own machine. The stand-in
uses `getblockchaininfo`, `getblock`, `getrawmempool`, `getrawtransaction`, `validateaddress`, `decodescript`,
`estimatesmartfee`, `getmempoolinfo`, `getnetworkinfo`, `testmempoolaccept` and `sendrawtransaction`.

The tests start it through `StandIn` in `app/src/test/kotlin/org/polarka/wallet/ServerSupport.kt`, on top of a
regtest node of their own. `StandInTest` tells one wallet's story through it.

## Switches for tests

`POST /__standin/set` with a JSON object. The path is not part of the API.

| Switch | What happens |
|---|---|
| `{"stale": true}` | the index says it is not up to date: what is read from the index is answered 503, `/ready` too; what comes straight from the node still answers |
| `{"busy": N}` | the next N requests are answered 429 with `Retry-After: 1`, as the real server does above its limit of requests |
| `{"hold": true}` | a transaction handed over for broadcast is not passed on; the answer is the node's error, 502 |
| `{"refuse": "WHY"}` | the server says no and keeps what it was shown: validation answers that the node does not take the transaction (WHY), broadcast answers the node's error, the node is asked neither time; `""` turns it off |
| `{"hide": true}` | the coins that a kept transaction spends are left out of `/v1/utxos` |

The answer names every switch, and under `kept` the transactions that a server which said no was shown. The
last two switches play a server that is not honest. The real server is not known to be one; a wallet has to
stay safe with one all the same, and `review/emulator/t07_server_says_no.py` shows on a phone that it does.

## How it was compared with the real server

On 14 November 2026 the stand-in ran on a node of the main network and was asked the same questions as the
public Northbound server, each pair at the same block. Everything asked was reading. The scripts are
`compare.py` and `compare_errors.py`.

- Five sets of addresses of a mining pool and of people it pays, which is public chain data: activity, coins,
  and the newest pages of history. All alike: the activity of every set, five lists of coins (0, 31, 31, 2,358
  and 5,341 coins, in the same order and with the same fields) and 1,602 lines of history. The lines were
  incoming payments, payments with every input from the addresses asked about, and payments with inputs of
  both kinds where something shows as sent.
- Eighteen requests that have to be refused or sit at an edge (too many addresses, no address, an address of
  another network, a body that is not JSON, no content type, limits and cursors that cannot be, ids that are
  not ids, an unknown and a known transaction, a transaction to be checked that is not hexadecimal, a path that
  is not there, the wrong method, an empty list).
  Status and message were alike in seventeen. The eighteenth had the right status and other words (an address
  of another network), and the stand-in was put right.
- The tip, the fee estimate and `/ready` were alike.

Forty-one requests went to the real server for this.

What could not be seen on the real server, and so rests on the reading of its sources alone: lines for
transactions that wait for a block, the line for inputs of both kinds where more comes back than was put in,
409 for an index that changed between two pages, 429, 503, and everything about checking and sending a
transaction beyond the refusal of text that is not hexadecimal. For a legacy address of another network the stand-in words its refusal differently from the real
server (the status is the same).

Where the stand-in and the real server differ, the real server is right.
