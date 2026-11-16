# The Northbound wallet server, as this wallet reads it

Northbound (github.com/nova9/northbound) is an independent wallet for TrueNorth with a server that stands in
front of a TrueNorth Core node and keeps an index of addresses. This note says what `ServerClient` (module
`node`) takes for granted about such a server, and where that comes from.

It was written from the server's sources: first at the tag v0.1.0-rc.5 and commit 846e444, and again at commit
07523b6 of 15 November 2026. The server's code was read, not built or run. Read-only requests to the public
server on 14 and 15 November 2026 came back in these shapes.

The paths of version 1 have stayed as they were. The shapes have not. On 15 November 2026 the lines of history
changed and coins began to come in pages (commits d72a5e1, e3376a1 and 66fa1d9), and nothing in these answers
says which shapes a server speaks. Polarka Wallet 0.6.0 read the earlier shapes and refuses the new history as an
answer in an unknown form; 0.6.1 reads the shapes below, and refuses the earlier history in the same way.

## Calls

| Call | Request | Answer |
|---|---|---|
| `GET /v1/chain/tip` | | `{"height"}` |
| `GET /v1/fees/estimate` | | `{"atomic_units_per_vbyte","target_blocks"}` |
| `POST /v1/addresses/activity` | `{"addresses":[...]}` | `{"used_addresses":[...],"index"}` |
| `POST /v1/utxos` | `{"addresses":[...],"cursor","limit"}` | `{"utxos":[...],"next_cursor","index"}` |
| `POST /v1/transactions` | `{"addresses":[...],"cursor","limit"}` | `{"transactions":[...],"next_cursor","index"}` |
| `POST /v1/transactions/validate` | `{"transaction_hex"}` | `{"allowed","rejection_reason"}` |
| `POST /v1/transactions/broadcast` | `{"transaction_hex"}` | `{"transaction_id"}` |
| `POST /v1/transactions/status` | `{"transaction_id"}` | `{"known"}` |

Amounts are whole numbers of base units. An error has the body `{"message"}`.

`GET /v2/fees/estimate` gives the rate for 1,000 virtual bytes (`atomic_units_per_kvbyte`) and so does not
round it up to a whole unit per byte. The client does not use it: the wallet takes no fee estimate from a
server and offers its three fixed rates.

## What the answers mean

- **Coins** (`utxos`): outputs that are in a block and that nothing spends. An output that a transaction waiting
  for a block spends is not listed, and an output of a waiting transaction is not listed either. Each coin has
  `transaction_id`, `output_index`, `amount`, `script_pubkey`, `block_height`, `confirmations` (the index height
  less the block height, plus one) and `coinbase`. They come a page at a time, ordered by block, id and output
  number: a thousand to a page unless the request names a `limit` (1 to 10,000). `next_cursor` leads to the
  next page and is null on the last. A cursor holds for the block and the picture of the waiting transactions
  it was made at; used later, it is answered 409, and every page has to be read again. The client names no
  limit and takes the size of page the server gives.
- **Activity**: an address counts as used when an output pays to it, in a block or in a waiting transaction.
- **History**: newest first, the waiting transactions before those in blocks. Every line is summed up against
  the addresses of its own request: `net_amount` is what the transaction's outputs pay to them less what its
  inputs take from them, a whole number that can be below zero. `network_fee` is the fee of the whole
  transaction, whoever paid it, and null for a block reward. A waiting transaction has no `block_height` and
  0 confirmations; its `timestamp` is when the server first saw it. `limit` is 1 to 200. When a block or a
  waiting transaction arrives between two pages the server answers 409 and the reading has to start again.
- **Index** (`index`, with every answer read from the index): `ready`, `indexed_height`, `indexed_hash`,
  `observed_node_height`, `observed_node_hash`, `pending_available`, `pending_revision` and three fields about
  age. The client takes an answer only when the index is ready, knows the waiting transactions, and is at the
  height and hash its node reports. This is the rule the Northbound wallet applies itself. `pending_revision`
  goes up whenever the index takes a new picture of the waiting transactions, which it does when they or the
  block change. Two answers with the same block and the same number were read from the same picture.
- **Validate** asks the node whether it would take the transaction (`testmempoolaccept`). The server sees the
  whole signed transaction at that point. The fee is not returned.
- **Broadcast** passes the transaction on (`sendrawtransaction`). Every error of the node comes back as 502:
  a refusal, a transaction that is already in a block, and a node that is down look the same. After an error
  only **status** tells what the server knows.
- **Status**: `known` is true for a transaction in the index (in a block or waiting) or known to the node.

## Limits

- At most 100 addresses in one request (400 otherwise) and a body of 1 MB. The server sets no bound on the
  coins of an address over all pages. The client reads at most 20,000 coins for one question
  (`ServerClient.MAX_COINS`) and says so when there are more.
- Since commit 846e444, 100 requests a second for all clients of one server together; above that 429 with
  `Retry-After: 1`. Requests that were refused do not reach the index or the node.
- 503 when the index is busy, still catching up, taking a new picture of the waiting transactions, or older
  than its allowed age.

## What follows for a wallet

- Coins and activity can be asked for in parts of a hundred addresses and put together exactly, provided all
  parts and all pages were read from the same picture: the same block and the same `pending_revision`.
  `ServerClient` reads coins again from the start when that changes in between (three readings at most), and
  refuses activity that was read across a change. Half a list of coins is never taken for a balance.
- A history can be put together from parts: the lines that several requests give for one transaction add up
  to what the transaction did to all their addresses. A wallet with more than a hundred addresses has an exact
  history that way.
- A line does not say how a movement is made up: what was received and what was spent, who was paid, and what
  of the fee the addresses bore. The wallet shows a payment as what it lost by it, with the fee of the whole
  transaction beside it when it lost at least that much. A block reward is known by having no fee.
- For a waiting payment of the wallet's own the line is a loss, and the coins it spends are gone from the list
  at once. What such a payment gives back, the change, is not in any answer. The wallet writes down what its
  payment put in when it makes it, and works the change out from that and from the server's line.
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
- Nothing in the answers below names the version of the shapes. A client learns of a change by failing to
  read an answer, as 0.6.0 did. The author of the server was asked on 15 November 2026 whether a change could
  be made visible to a client (github.com/nova9/northbound, issue 5). On 16 November 2026 he added `GET /info`,
  which answers with the release of the server, `{"version":"0.2.0-alpha.1"}` at first; a server from before
  that answers 404, and so did the public server on the morning of that day. The other answers were not
  changed by it. Polarka Wallet 0.6.1 does not ask `/info`.
