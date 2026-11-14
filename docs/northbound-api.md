# The Northbound wallet server, as this wallet reads it

Northbound (github.com/nova9/northbound) is an independent wallet for TrueNorth with a server that stands in
front of a TrueNorth Core node and keeps an index of addresses. Its author has said that the paths and shapes
of version 1 will stay as they are. This note says what `ServerClient` (module `node`) takes for granted about
such a server, and where that comes from.

It was written from the server's sources at the tag v0.1.0-rc.5 and commit 846e444. The server's code was read,
not built or run. Five read-only requests to the public server on 14 November 2026 came back in these shapes.

## Calls

| Call | Request | Answer |
|---|---|---|
| `GET /v1/chain/tip` | | `{"height"}` |
| `GET /v1/fees/estimate` | | `{"atomic_units_per_vbyte","target_blocks"}` |
| `POST /v1/addresses/activity` | `{"addresses":[...]}` | `{"used_addresses":[...],"index"}` |
| `POST /v1/utxos` | `{"addresses":[...]}` | `{"utxos":[...],"index"}` |
| `POST /v1/transactions` | `{"addresses":[...],"cursor","limit"}` | `{"transactions":[...],"next_cursor","index"}` |
| `POST /v1/transactions/validate` | `{"transaction_hex"}` | `{"allowed","rejection_reason"}` |
| `POST /v1/transactions/broadcast` | `{"transaction_hex"}` | `{"transaction_id"}` |
| `POST /v1/transactions/status` | `{"transaction_id"}` | `{"known"}` |

Amounts are whole numbers of base units. An error has the body `{"message"}`.

Commit a531965 adds `GET /v2/fees/estimate`, which gives the rate for 1,000 virtual bytes
(`atomic_units_per_kvbyte`) and so does not round it up to a whole unit per byte. The client does not use it:
the wallet takes no fee estimate from a server and offers its three fixed rates. When this note was first
written, in the afternoon of 14 November 2026, the public server did not have that call; in the evening of
the same day it answered 1,002 for 1,000 virtual bytes, where `/v1/fees/estimate` answered 2 for one byte.

## What the answers mean

- **Coins** (`utxos`): outputs that are in a block and that nothing spends. An output that a transaction waiting
  for a block spends is not listed, and an output of a waiting transaction is not listed either. Each coin has
  `transaction_id`, `output_index`, `amount`, `script_pubkey`, `block_height`, `confirmations` (the index height
  less the block height, plus one) and `coinbase`.
- **Activity**: an address counts as used when an output pays to it, in a block or in a waiting transaction.
- **History**: newest first, the waiting transactions before those in blocks. Every line is summed up against
  the addresses of its own request. `received_amount` is what the outputs pay to them. With no input from them
  the line is an incoming payment and `received_at` names the first address paid. With every input from them,
  `sent_amount` is what goes elsewhere and `fee` is given. With inputs of both kinds, `sent_amount` is the own
  input less what came back, not below zero, and `fee` is missing. A waiting transaction has no `block_height`
  and 0 confirmations. `limit` is 1 to 200. When a block or a waiting transaction arrives between two pages the
  server answers 409 and the reading has to start again.
- **Index** (`index`, with every answer read from the index): `ready`, `indexed_height`, `indexed_hash`,
  `observed_node_height`, `observed_node_hash`, `pending_available` and three fields about age. The client takes
  an answer only when the index is ready, knows the waiting transactions, and is at the height and hash its
  node reports. This is the rule the Northbound wallet applies itself.
- **Validate** asks the node whether it would take the transaction (`testmempoolaccept`). The server sees the
  whole signed transaction at that point. The fee is not returned.
- **Broadcast** passes the transaction on (`sendrawtransaction`). Every error of the node comes back as 502:
  a refusal, a transaction that is already in a block, and a node that is down look the same. After an error
  only **status** tells what the server knows.
- **Status**: `known` is true for a transaction in the index (in a block or waiting) or known to the node.

## Limits

- At most 100 addresses in one request (400 otherwise), a body of 1 MB, and 10,000 coins in one answer: a
  request that would list more is refused with 400 and the words "request exceeds the 10,000 output limit".
- Since commit 846e444, 100 requests a second for all clients of one server together; above that 429 with
  `Retry-After: 1`. Requests that were refused do not reach the index or the node.
- 503 when the index is busy, still catching up, or older than its allowed age.

## What follows for a wallet

- Coins and activity can be asked for in parts of a hundred addresses and put together exactly, provided all
  parts were read at the same block. `ServerClient` refuses a mixed answer.
- A history cannot be put together from parts: a payment whose inputs are in one request and whose change is
  in another comes back as two lines that are each true only for their own addresses, and in one case the
  amount cannot be recovered. A wallet has an exact history only while all its addresses fit one request.
- A lie about the amount of a coin does not make the wallet overpay a fee. The signature covers the amounts of
  all the outputs it spends (the BIP-341 signature hash), so a transaction built on a wrong amount is not valid.
- The server learns every address it is asked about and where the questions come from. It can show a wrong
  balance or history, hold a payment back, or say that it is in a block. It cannot spend.
- Nothing in the API says which network a server is on. A server of another network refuses the wallet's
  addresses.
- With **validate** as with **broadcast** the server has the whole signed transaction, and it could keep it
  whatever it answers. A wallet must not forget a payment because the server said no to it. This wallet
  keeps such a payment as one that did not go through and builds its next payment on one of its coins.
- The server does not say what spent a coin. A coin that it does not list may have been spent in a block, may
  be spent by a waiting transaction, or may simply have been left out. This wallet concludes nothing from a
  coin that is missing: a payment of its own counts as overtaken only when the server shows, in a block,
  another payment of the same wallet that spends one of its coins.
- No answer is waited for without end. Every question has a time limit as a whole (`ServerClient.DEADLINE_MS`),
  beside the limits for a connection and for a single read.
