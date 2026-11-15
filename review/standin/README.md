# A stand-in for a Northbound wallet server

`northbound_standin.py` answers the requests of the Northbound API, version 1, from a TrueNorth Core node. The
wallet's tests use it in place of the real server (github.com/nova9/northbound). The real server's code is
not built or run anywhere in this repository: the stand-in was written from its sources (tag v0.1.0-rc.5 and
the commits up to 07523b6 of 15 November 2026) and then compared with the public server. What the API is and
what the wallet takes for granted about it is in `docs/northbound-api.md`.

On 15 November 2026 the real server changed two answers: coins come a page at a time, and a line of history
is a signed amount with the fee of the whole transaction beside it. The stand-in answers in the new shapes
only.

It is a test double. It listens on 127.0.0.1 only, keeps no database, answers one request at a time and
trusts its node. It is not meant to serve anybody.

## Running it

    python3 -B northbound_standin.py --rpc-url http://127.0.0.1:PORT --cookie DATADIR/regtest/.cookie

It prints `listening PORT` when it is ready. `--port` names a port (0 takes a free one), `--warm` reads the
whole chain first, `--exit-with-stdin` makes it stop when its standard input closes, so that it never outlives
the test that started it. It needs Python 3.9 or later and nothing beyond the standard library; it has been
run with 3.14.

The node is asked with its cookie, the login a node writes for programs on its own machine. The stand-in
uses `getblockchaininfo`, `getblock`, `getrawmempool`, `getrawtransaction`, `validateaddress`,
`estimatesmartfee`, `getmempoolinfo`, `getnetworkinfo`, `testmempoolaccept`, `decoderawtransaction` and
`sendrawtransaction`.

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
| `{"page": N}` | `/v1/utxos` gives N coins to a page when the request names no limit, so that a test needs no thousand coins to see a second page; `0` is the real server's thousand |

The answer names every switch, and under `kept` the transactions that a server which said no was shown. The
switches `refuse` and `hide` play a server that is not honest. The real server is not known to be one; a wallet has to
stay safe with one all the same, and `review/emulator/t07_server_says_no.py` shows on a phone that it does.

## How it was compared with the real server

Twice: on 14 November 2026 with the server's earlier answers, and on 15 November 2026 after the change, for
what the change brought.

### 15 November 2026: pages, and what must be refused about them

`compare_pages.py` asked the stand-in, on a regtest node of its own, and the public server the same 51
questions about pages of coins and of history: a page, the page after it, no limit, limits that cannot be,
cursors that are no cursors, cursors with fields that cannot be, a cursor of another block and of another
picture of the waiting transactions (409), a cursor with a field too many. The status, the words of every
refusal, the fields of an answer, of a coin, of a line, of a cursor and of the index, and the number of
entries on a page were alike in all 51. The two stood on different chains, which these questions do not
depend on. Fifty-seven requests went to the real server, all of them reading, naming one address that is
public chain data.

What the new lines of history say was not compared entry by entry with the stand-in, since that needs both
on one chain. It was checked on the public server itself in two ways, reading only and with addresses that
are public chain data. For seven addresses that block rewards went to in October, the lines of the whole
history added up to what the coins of the address hold (`check_sums.py`, 20 requests; all their lines were
block rewards). And for four payments and one block reward of a mining pool's address, `net_amount` and
`network_fee` were what the data of a TrueNorth Core node give for those transactions: what the outputs pay
to the address less what the inputs take from it, and the inputs less the outputs.

### 14 November 2026: the earlier answers

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

What could not be seen on the real server, and so rests on the reading of its sources alone: 429, 503, and
everything about checking and sending a transaction beyond the refusal of text that is not hexadecimal. A
line for a transaction that waits for a block was read from the real server once, on 15 November, by the
wallet's own client: no block, no confirmations, a loss and a fee. (The lines of history compared on
14 November were of the earlier form, which the server no longer gives.) For a legacy address of another network the stand-in words its refusal differently from the real
server (the status is the same).

Where the stand-in and the real server differ, the real server is right.
