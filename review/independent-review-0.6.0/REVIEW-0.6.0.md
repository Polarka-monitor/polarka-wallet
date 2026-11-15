# Independent review of Polarka Wallet 0.6.0 (commits 86d735e..893f6b7)

Reviewer: an AI model, working from the brief in `BRIEF.md`. This is a review, not an audit. It covers
the eight commits named in the brief, read against the Northbound sources (`nova9/northbound`, local read-only
copy, head ef4999c) and exercised with the author's tests and with four tests of my own.

## Verdict

The client of the server (`ServerClient`) is careful and I found nothing wrong in how it reads answers. The
history assembly in `ServerEngine` holds the author's claim for an honest server as far as I could test it.
The setup and the twelve words are sound. But the safeguard against paying twice does **not** hold over a
wallet server, and two of the three ways it fails need nothing more than a server that lies, which the brief
says to assume:

1. A failed `/validate` makes the app forget the payment, although the whole signed transaction has already
   been given to the server. A server that keeps it, says "no", hides that coin next time and then broadcasts
   both transactions makes the wallet pay the same shop twice (shown, H1).
2. A "did not go through" payment is let go as soon as its coin is missing from the server's list and the
   server says it does not know the payment. Absence is taken as "a block spent it". A lying server thereby
   switches the safeguard off and can again get the payment made twice (shown, H2).
3. Even with an honest server, the payment that did not go through is let go while the payment built to
   stand against it is merely waiting in the mempool (shown, M1).

The screen text "It cannot take your coins" stays true, but the texts promise more than that: "Nothing was
sent" after a refusal is false, and the first screen does not say that a server can lead the wallet into a
second payment. I would not ship 0.6 with a third-party server as the pre-filled default until H1 and H2 are
fixed. The node path that the owner's existing wallet uses was not changed in substance and its tests pass.

## Findings by severity

| # | Severity | Title | Place |
|---|---|---|---|
| H1 | high | A refused or failed check forgets a payment the server already holds; a hostile server makes the wallet pay twice | `ServerEngine.kt:431-441`, `WalletModel.kt:789-798`, `strings.xml:300` |
| H2 | high | Absence of a coin is taken as "spent in a block"; a hostile server switches off the guard against a second payment | `ServerEngine.kt:229-240`, `WalletModel.kt:425` |
| M1 | medium | Honest server: a payment that did not go through is let go while its rival only waits | `ServerEngine.kt:224-236` |
| L1 | low | `judge` overwrites a payment's own id in `spentBy` with ANOTHER when a dropped payment shares the coin | `ServerEngine.kt:233-240` |
| L2 | low | Every HTTP 400 is shown as "a server of another network" | `ServerEngine.kt:121-125` |
| L3 | low | No overall time limit per request; a slow server can hold a look or a hand-over without end | `ServerClient.kt:102-139` |
| L4 | low | Screen texts promise more than the code does, or leave out what the server learns | `strings.xml:184, 286, 300, 316` (and `values-cs`) |
| N1 | note | "On its way" can count an amount that another waiting transaction already spends | `ServerEngine.kt:287` |
| N2 | note | Notifications through a server: one received amount per part, decided by the first address paid | `ServerEngine.kt:357-362` |
| N3 | note | A server that says "known" every time keeps a payment waiting for ever | `ServerEngine.kt:228-229`, `deliver` |
| N4 | note | Coins, activity and history of one look may come from different mempool states | `ServerEngine.look` |
| N5 | note | Setup refuses plain http even to a private address, the client allows it | `Setup.kt:89` vs `ServerClient.kt:73` |

Counts: critical 0, high 2, medium 1, low 4, note 5.

## Findings

The four tests of my own are in `polarka-wallet/app/src/test/kotlin/org/polarka/wallet/ReviewerServerTest.kt`
(Robolectric, on the author's `ServerNode`, `StandIn` and `ServerRelay`; regtest and the stand-in on this
machine only). Each test asserts the **faulty** outcome, so a passing test means the fault is there. All four
pass at 893f6b7 (logs `logs/rev1.log` .. `logs/rev4.log`).

### H1 (high). A failed check forgets a payment that the server already holds

**Where.** `ServerEngine.deliver` (`ServerEngine.kt:431-441`) sends the whole signed transaction to
`/v1/transactions/validate` and turns any `NodeError` there (time-out, cannot reach, 502, 503 after waiting)
and any `allowed: false` into a thrown `EngineError`. `WalletModel.sendNow` (`WalletModel.kt:789-798`) treats
every thrown error as "the node itself refused it: it is certain that it did not go out" and removes the
payment from `pending` without keeping it among the payments that did not go through. The KDoc of `deliver`
says "In both cases nothing was handed over for sending", and `problem_rejected_server` says "Nothing was sent".

**Why it is wrong.** At that point the signed transaction has left the phone: the server has it. Neither the
"no" nor its reason nor a failed answer can be checked by the phone. Nothing stops a server from keeping the
transaction and broadcasting it later. The app has then forgotten it, the next payment is not made to stand
against it, and the server decides which coins the next plan sees (it can leave the first coin out of
`/v1/utxos`). The safeguard of the app exists exactly for "a transaction that could still arrive from
elsewhere"; here it is switched off by the party that holds the transaction.

**Shown.** `ReviewerServerTest.aServerThatKeepsWhatItIsAskedToCheckMakesTheWalletPayTwice`: the relay keeps
the hex from `/validate` and answers `allowed:false, "min relay fee not met"`; the model shows REJECTED and has
neither a pending nor a dropped entry. The relay then hides that coin from `/v1/utxos`; the owner pays again;
the second plan is built on the other coin and stands against nothing (`against == 0`). The kept transaction
is broadcast and a block is mined: the shop holds 10 NORTH for one 5 NORTH purchase. Passes.

The node path has the same exposure in a different place: `Engine.sign` calls `testmempoolaccept` before the
payment is written down, so a hostile public node also gets the transaction and a refusal leaves no record.
That code is outside the eight commits; I mention it because the fix should cover both.

**What would put it right.** Once the signed transaction has been sent anywhere, never forget it on the word
of the receiver. On any failure of `/validate` (including `allowed:false`), keep the payment, either as
"outcome not known" (`unsure`) or directly as a payment that did not go through (`Dropped`), so that the next
payment spends one of its coins. Alternatively drop the `/validate` round trip (the phone can check fee and
amounts itself; the node's verdict adds little that the broadcast does not give) and let only the hand-over
decide. Change `problem_rejected_server` (and the Czech text) so that it does not say "Nothing was sent".

### H2 (high). Absence of a coin is taken as "spent in a block"

**Where.** `ServerEngine.judge` (`ServerEngine.kt:224-240`): for a payment that is neither in the history,
nor made a coin, nor in the waiting lines, nor `known`, every input coin that the server does not list is put
into `spentBy` as `ANOTHER` ("a transaction in a block has spent it"). `WalletModel.settle` (`WalletModel.kt:425`)
then lets a dropped payment go (`beaten`), silently, in a single look.

**Why it is wrong.** For a node the "spent by" came from block data; here it is inferred from omissions only
(a coin missing from `/v1/utxos`, a payment missing from `/v1/transactions`, `known:false`). All three are under
the server's control. A server that has the transaction (it got it at the hand-over, or at `/validate`, see H1)
can make the app forget the remembered payment and then let both the old and the new payment through. With an
honest server the inference is also weaker than the KDoc says: see M1.

**Shown.** `ReviewerServerTest.aServerThatHidesACoinUndoesTheGuardAgainstASecondPayment`: the first payment's
broadcast fails (502, `known:false`), two looks call it gone (PAYMENT_GONE, one dropped entry). The relay then
leaves its coin out of `/v1/utxos`; one look later the dropped entry is gone (no notice is shown for that), the
next payment has `against == 0`, and after the kept transaction is broadcast the shop holds 10 NORTH for one
5 NORTH purchase. Passes.

**What would put it right.** Over a server, do not let a payment that did not go through go because something
is missing. Possible rules: keep it until the owner forgets it (`forgetDropped` exists), or until a block line
of the history positively shows a transaction other than the payment taking an input from the address of that
coin, and that line is some blocks deep. Say on the screen that through a server such entries stay until the
owner removes them.

### M1 (medium). Honest server: the payment that did not go through is let go while its rival only waits

**Where.** `ServerEngine.judge`, `ServerEngine.kt:226` and `:235`. `stranger` is true only for a waiting
transaction that is none of the wallet's own payments. When the coin of a dropped payment D is spent by the
wallet's own waiting payment P (which is what the safeguard makes happen: P is built on D's coin), the server
leaves the coin out of `/v1/utxos`, D is not known, `stranger` is false, and the coin gets `ANOTHER`. D is
let go in the same look, although P is in no block.

**Why it matters.** `Snapshot.spentBy` is documented as coins "which a transaction in a block has spent", and
`settle` lets go only what "can never arrive". D can still arrive as long as P is only waiting. If P then does
not go through (evicted, too low a fee, a server that held it), P becomes the only remembered payment; the
next payment must spend one coin of P, and if P had more than one input and the planner takes the other one,
that payment and D can both reach a block. The author's test `aPaymentThatDidNotGoThroughOneThatWasBeatenAndOneThatCameBack`
checks a stranger's waiting transaction, not the wallet's own one, so it does not see this.

**Shown.** `ReviewerServerTest.aPaymentThatDidNotGoThroughIsLetGoWhileItsRivalOnlyWaits`, with the honest
stand-in: D held by the server (`hold`), called gone; the next payment stands against D (`against == 1`) and
is in the mempool; one look later `dropped` is empty while the mempool still holds P. Passes.

**What would put it right.** In `judge`, treat a coin that is an input of one of the wallet's own payments
listed among the waiting lines (or `held`) as tied (`inWaiting`), never as `ANOTHER`. Add the case to
`ServerEngineTest` and to the model tests.

### L1 (low). `judge` overwrites a payment's own id in `spentBy`

**Where.** `ServerEngine.kt:233-240`. Pending payments are judged first and may write `spentBy[c] = Q`;
dropped payments are judged next and write `spentBy[c] = ANOTHER` over it when they share the coin c.

**Shown.** `ReviewerServerTest.aPaymentInABlockIsCalledBeaten` (engine part): Q in a block, inputs in two
parts, one part gets back more than it put in, so Q has no history line; with a dropped P on the same coins,
both coins read `another transaction`; without P they read Q's id. I then tried to turn this into a false
"payment beaten" message through the model and could not: P is let go in the first look and the next look
sees Q's id again, so the model ends correct (that half of the test asserts the recovery). Low, because it
depends on the order of settling and costs one extra look; fix by not overwriting an entry that is already set.

### L2 (low). Every HTTP 400 is shown as "a server of another network"

`ServerEngine.asked` (`ServerEngine.kt:121-125`) maps any 400 to `OTHER_CHAIN`. The real server also answers
400 for "request exceeds the 10,000 output limit" (`indexer/src/queries.rs:122-125`), for a history limit or
cursor it does not take, and for more than 100 addresses. A mining wallet with more than 10,000 coins in one
part (pool payouts) would be told that the server works for another network, and could not be used at all.
Fix: tell the cases apart (the server's message, or ask once with an address of the chain that is surely
valid), and say "too many coins" as what it is.

### L3 (low). No overall time limit per request

`ServerClient.once` sets `connectTimeout` and `readTimeout` (30 s each, per read). A server that sends a byte
every 29 seconds keeps one request open for as long as it likes, up to 8 MB. The per-server lock
(`synchronized(lock)`) makes every other request to that server, the notifier's included, wait behind it, and
the forty-second limit of `deliver` is only checked between tries. Fix: an overall deadline per request (and
for the whole of `deliver`), enforced by closing the connection from a timer.

### L4 (low). Texts

- `problem_rejected_server` (`strings.xml:300`): "Nothing was sent." False: the server got the signed
  transaction at `/validate` (H1). The reason in brackets is also the server's word, not the node's.
- `server_sees` (`strings.xml:286`) lists what a server can do (wrong balance, keep a payment back) but not
  that it can make the wallet pay twice (H1, H2); after the fixes this sentence can stay. It also says the
  server sees "all the addresses of your wallet"; it is told the watched addresses of the setup in the same
  requests, and with "Empty a paper address" forty addresses of the paper's words as well, which links them
  to this wallet. `paper_text` (`strings.xml:184`) does not say so for a server.
- `history_not_whole` (`strings.xml:316`) gives one reason only ("more than a hundred addresses"). A wallet in
  one part also gets the note when a transaction mixes its inputs with somebody else's and it got back at
  least as much as it put in (`movement`, `some.any { it.sentSat == 0L }`), or when a part has more than 2,000
  lines. "The balance and the coins are complete" is true for an honest server and for the keys looked at.
- The Czech file `values-cs/strings.xml` carries the same sentences; I did not read it line by line.

### N1 (note). "On its way" can count what another waiting transaction spends

`arrivingSat` (`ServerEngine.kt:287`) sums `received_amount` of all waiting lines. `/v1/utxos` lists only
outputs in blocks, so an output of a waiting transaction that a second waiting transaction spends is counted
once as arriving and again through the second one's change. Needs a chain of waiting transactions over the
wallet's outputs (another phone with the same words). Display only.

### N2 (note). Notifications through a server

`since` (`ServerEngine.kt:357-362`) counts a part's `received_amount` only if `received_at`, the first wallet
address paid in that part, is a receiving key. A payment from outside to a change key is not told; one that
pays a receiving and a change key of the same part is told with both amounts together. Watched addresses are
not affected.

### N3 (note). A payment can wait for ever

A server that answers `known: true` and lists nothing keeps a payment among the waiting ones for ever
(`judge`, `server.known`), and `deliver` reports it as sent. With an honest server the same happens for a
payment in a block that is older than the 2,000 lines read for its part and left no coin (the KDoc says so).
There is no owner action to remove a waiting payment, unlike a dropped one.

### N4 (note). One look, several mempool states

`look` compares block hashes across calls, not the server's `pending_revision`. Coins and history read a moment
apart can disagree about a waiting transaction (a coin listed as free and also spent by a waiting payment), so
one look can overstate the balance by that payment's change. Judging is not affected as far as I can see.

### N5 (note). http to private addresses

`ServerClient` takes plain http to a private address (as the brief describes), but `Setup.checkedUrl`
(`Setup.kt:89`) refuses any http, so an owner cannot name a server on the home network without TLS. Stricter
than needed, not unsafe. The 100.64.0.0/10 range counted as private by `NodeClient.isPrivate` is also carrier
NAT space; http there crosses the carrier's network (pre-existing, node path).

## What I checked and found sound

- **Tests.** The whole suite at 893f6b7 with my test file added: `:core:test` 34, `:node:test` 24,
  `:app:testDebugUnitTest` 112 (my first three included), all passing; one skipped, `PublicNodeTest`, which needs
  `POLARKA_GATEWAY` (node path, outside the eight commits). Log `logs/full.log`.
- **Mutants of my own** (`tools/mutants.sh`, each file restored from git afterwards, `git status` clean apart
  from my test): redirects followed; no depth check on coins; index height not compared with its node's;
  `movement` keeps a line whose part got back at least as much as it put in; cut-short parts (`unsure`)
  ignored. All five were caught by the author's tests. The tests I read (`ServerClientTest`,
  `ServerEngineTest`, `ServerPayTest`, `ServerModelTest`) test what their names say; what they miss is listed
  under M1 and H1/H2 (no test treats `/validate` or an omission as hostile).
- **ServerClient** against the server's sources (`server/src/*.rs`, `indexer/src/{queries,history,status}.rs`):
  https, or http only to a literal private address (names are not looked up); no query, path, user info;
  redirects not followed; answers capped at 8 MB including error bodies; numbers only as unsigned JSON
  integers with upper bounds; ids 64 hex; coins must sit within the index tip with the matching depth; parts
  of one answer must share block and hash (`MOVED`); an index that is not ready, has no mempool view, or is not
  at its node's height and hash is refused (as the server's own `read_status` defines readiness); history
  restarts on 409, refuses an empty page with a cursor, refuses duplicates; 429/503 waited for, bounded.
  `summarize_transaction` in `indexer/src/history.rs` matches the meaning `docs/northbound-api.md` gives to
  `received_at`, `sent_amount` and `fee`, including the mixed case.
- **History assembly** (`ServerEngine.movement`, `histories`): for an honest server I found no case that
  produces a false line; the cases are exact for inputs in one part, give the wallet's input and no fee for
  inputs in several parts, and leave out what cannot be known; cut-short parts are handled by `unsure`.
- **Coins and keys.** Every coin must sit on a script of the wallet (or of the watched addresses); the server
  cannot move the key numbers beyond `allowed` (`settle` caps at `allowed - 1`, and `allowed` is derived from
  them, so there is no ratchet); the number of addresses named is 2 x 40 by default and more only after the
  owner says so; `lookTo` is reset when the setup changes.
- **Paying.** The fee rate is the owner's (`feeEstimate` is not used); the change script is derived on the
  phone; a coin whose amount the server misstates gives an invalid signature (author's test, and the sighash
  argument in the docs); signing sends nothing; the payment is written down before the transaction leaves the
  phone; a broadcast answer naming another id is not believed.
- **Setup.** Form 3 must name `server` and must not name `node`, user or password; forms 1 and 2 are parsed
  as before and `toJson` of a node setup is unchanged; `Engine` refuses a server setup, and all construction
  goes through `Gateway.of`, so a server is never given the descriptors. A 0.5.1 setup and wallet file are read
  as before as far as code reading and `SetupTest` show.
- **Twelve words.** `SecureRandom`, 16 or 32 bytes, anything else refused, entropy zeroed after use; restore,
  check of the paper and the confirmation step use `words.size`. Northbound's wallet uses purpose 88, coin type
  1867 and only 12 words (`wallet/src/keys.rs`), consistent with the claim that it opens such words.

## What I did not check

- The node path (`Engine`) beyond the diff, and nothing on a real device or emulator; no UI test was run by me
  beyond the Robolectric tests in the suite.
- That Northbound's wallet derives the same P2QRH addresses from the same words (only the path constants were
  compared), so the sentence "the Northbound wallet for computers opens them too" rests on that.
- The stand-in's fidelity beyond what my tests needed; the real server was not contacted.
- The Czech texts and the guides (`docs/guide.md`, `docs/navod.md`) line by line.
- Thread-safety of `WalletModel` under concurrent looks and sends beyond what the tests exercise.
- Whether TrueNorth's signature hash commits to all input amounts and scripts (taken from the brief and the
  author's docs and test).

No file I read asked me to do anything outside the task. No secrets were met in this folder.

## After the fixes (d739709)

Second look, by the same reviewer, an AI model. Commit d739709 (on top of 893f6b7) was fetched read-only from
the author's repository into my clone and checked out there; the author's table in
`north-wallet/review/independent-review-0.6.0/README.md` (working tree) was read there and not copied. I read
the whole diff of d739709 (`ServerEngine`, `Gateway`, `Engine`, `WalletModel`, `Tabs`, `Parts`, `ServerClient`,
the English texts, the new tests), ran my four tests and one new test of mine, and ran the full suite.

### Verdict after the fixes

The three findings that made the wallet pay twice are closed as described: a payment that a server or a
public node refused is kept, nothing is concluded from a coin that is missing, and a payment that did not go
through is held until a payment of the wallet that spends one of its coins is shown in a block (which always
conflicts with it). I found no way left for a lying server to get two payments for one purchase into blocks
**without the owner acting against what the screen tells**. Two such ways remain through the owner's hands and
are now named on the screen only in part (S1 below). Nothing new of high or medium severity. The owner's own
node behaves exactly as at 893f6b7.

### Finding by finding

| Finding | Status | Remark |
|---|---|---|
| H1 | closed | `ServerEngine.deliver` (`ServerEngine.kt:464-484`) throws only on an explicit `allowed:false`; a check that fails or times out is followed by the hand-over. `WalletModel.sendNow` (`WalletModel.kt:799-819`) keeps such a refusal as `Dropped` unless `ownersNode`. Public node: `Engine.sign` asks `testmempoolaccept` only for the owner's node, `Engine.deliver` asks it for a public node after the payment is written down; a refusal at `sendrawtransaction` (NodeError) takes the same kept branch. My test `aServerThatKeepsWhatItIsAskedToCheckMakesTheWalletPayTwice` now fails at its first assertion (REFUSED_KEPT instead of REJECTED). |
| H2 | closed | `ANOTHER` is gone; `judge` (`ServerEngine.kt:238-268`) writes `spentBy` only for payments of the wallet shown in a block (history line with a height, or a listed coin of their making). Because `spentBy` only ever gets the id of a payment whose own inputs include the coin, "beaten" now implies a real conflict. My test `aServerThatHidesACoinUndoesTheGuardAgainstASecondPayment` fails: after the coin is hidden the dropped entry stays (`dropped=1`, `spentBy={}`). |
| M1 | closed | Coins of the wallet's own waiting payments (`busy`) are tied, not spent (`ServerEngine.kt:267`). My test `aPaymentThatDidNotGoThroughIsLetGoWhileItsRivalOnlyWaits` fails: D stays while P waits. |
| L1 | closed | Nothing is written over an existing entry, and only own payments are written. My test `aPaymentInABlockIsCalledBeaten` fails: with P dropped, both coins carry Q's id. |
| L2 | closed | `asked` tells "output limit" apart (`TOO_MANY_COINS`). It matches on the server's English words; a server that words it differently falls back to OTHER_CHAIN, which is acceptable. |
| L3 | partly | Each question now has a deadline with a watchdog and a check between reads, and `asked` stops waiting when the time is over. But the deadline is counted from before `synchronized(lock)` in `call` (`ServerClient.kt:180-182`), and the wait for the lock is not bounded by it: a question waits for whatever question holds the lock (up to its own 90 s). Shown by my new test `aQuestionWaitsForTheLockLongerThanItsTime`: `validate(…, 1000 ms)` returned after 7.5 s, behind a slow tip question (it then failed as "not in time" without being asked, which is safe). For `deliver` this means the 20 s for the check and the "about a minute" for the hand-over hold only while no look or notifier question is in flight; in the worst case validate, broadcast and status each wait up to 90 s. The outcome stays safe (a failure ends as "outcome not known"), so this is low. Fix: `ReentrantLock.tryLock(remaining)` instead of `synchronized`. |
| L4 | closed | `problem_rejected_server`, `server_sees`, `paper_text_server`, `history_not_whole`, `dropped_text_server`, `dropped_no_coin_server`, the new `problem_refused_kept*` and `problem_too_many_coins` say what the code does. I read the English texts; the Czech ones and the guides only in the diff, not line by line. |

### My tests on d739709

All four of my original tests now fail, each at the assertion that stated the fault (logs
`logs/second-rev.log`; the only edit to them was replacing the removed constant `ServerEngine.ANOTHER` by its
former text so that the file compiles). That is the expected outcome. The author's `SixthLookTest` tells the
same four stories with the opposite assertions, and does not only seem to: it uses the same relay to keep the
transaction, hide the coin and answer in the server's place, it checks the state that matters (`dropped`
holds the payment, `spentBy` is empty, `EARLIER_PAYMENT` while the coin is hidden, `against == 1` for the next
plan, the kept transaction is refused by the node afterwards) and it ends with the shop's balance from the
node itself (paid once). The public node and own node cases go through a door in front of a real node and
count `testmempoolaccept` and `sendrawtransaction` calls, and check that the payment was or was not written
down first. `SixthLookTest.aPaymentThatAServerRefusedIsNotForgotten` and `aCoinThatAServerLeavesOutDoesNotUndoTheGuard`
end with the coin shown again; they do not cover a server that keeps it hidden (S1).

Full suite on d739709 with my file added (logs `logs/second-full.log`, `logs/second-node.log`): core 34, node
25, app 123, of which 5 are mine. The author's 177 tests: none failed; one, `PublicNodeTest`, was skipped here
because `POLARKA_GATEWAY` is not set in my environment. Of mine, the four old tests fail as expected and the new
lock test passes (it shows the remaining part of L3).

### What the fixes leave open, or open

**S1 (low). A lying server can still steer the owner into a second payment; of the three routes below the screen
covers (a) and (c), and (b) only in part.**
(a) It hides the coin of a payment that did not go through for good. Every payment is then refused
(`EARLIER_PAYMENT`), and the only way on is "Forget this payment". `dropped_no_coin_server` says plainly to
look through another server or a node of one's own first, and the forget dialog warns that it could arrive
beside a new payment. I find that enough.
(b) It keeps answering `known: true` for a payment it never passed on (or answers the hand-over with the right
id), or turns a dropped payment back into a waiting one (`back`). The payment then shows as "Sent, waiting for
a block" for ever (my earlier N3). A waiting payment is not something the next payment must stand against: its
coins are only left out (`used`). An owner who after a day pays the shop again makes a payment that does not
conflict with the first, and the server can then pass both on. Nothing on the screen says that a payment that
waits unusually long through a server should be checked through another server or a node before paying again
(which would settle it, as the author's table says). Suggested: such a note on a waiting entry after some
hours through a server, or an action "pay again in its place" that spends one of its coins. Low, because it
needs the owner to pay a second time while the app shows the first as waiting; the same holds for a public node.
(c) A server can also call a payment done (a listed coin of its making, or a line with a height). `server_sees`
now says so ("call a payment done that is not").

**S2 (low). Refusals now fill the list of payments that did not go through.** Every refusal by a server or a
public node is kept. A refused payment re-tried at the same fee is refused again and kept again; with
`MAX_DROPPED` (50) reached, the refusal stays pending and "outcome not known" (`WalletModel.kt:807-819`), and
`settle` never moves it on while the list is full (`WalletModel.kt:407`). All payments are then stopped by
`OUTCOME_OPEN`, and `problem_unsure_server` promises that a refresh will settle it, which it will not until the
owner forgets older entries. Honest refusals for reasons the phone could foresee (fee below the node's minimum)
lead there as well. Suggested: say so in the text when the list is full; consider not counting a refusal again
when an entry with the same inputs is already kept.

**S3 (note). The watchdog and the reader.** `once` (`ServerClient.kt:117-170`) cancels the watchdog in
`finally`; a watchdog that has already started may still call `disconnect()` on a connection whose answer was
read in full. On the JVM this closes at most an idle pooled connection and the next question fails once,
which every caller handles (a failed hand-over question ends in `known`/"outcome not known"). On Android the
`HttpURLConnection` is OkHttp's; I did not check its `disconnect` after completion. Low impact either way.

**S4 (note). Paper address.** With a server or a public node a refused emptying is shown and not kept (there
is no list for paper payments). With a wallet in the phone the destination is the wallet's own address and the
rest goes to a typed new paper address, so a second emptying of a kept transaction costs a fee and no coins.
Without a wallet in the phone the destination is typed and could be a third party; then the same route as H1
remains for paper. Note only.

**Owner's own node (3e).** Unchanged in behaviour: `ownersNode = !setup.isPublic`, `isPublic` is as at
893f6b7, `Engine.sign` still asks `testmempoolaccept` before anything is written and compares the fee
(`signed.feeSat`, which `Plan.sign` checks equal to `plan.feeSat`), `Engine.deliver` skips the new check for it,
and `sendNow` forgets its refusal as before. `SixthLookTest.theOwnersNodeIsAskedBeforeAnythingIsWrittenDownAndItsNoIsTheEnd`
checks this through a door in front of a real node.

### Not checked in the second look

The Czech texts and guides line by line; Android's `HttpURLConnection` behaviour under `disconnect` from
another thread; the stand-in beyond the tests; nothing on a device or emulator; the 24 mutants of the author
(I did not rerun them; my five earlier mutants were not rerun on d739709 either).

## After the second look (c726e4d)

Third look, by the same reviewer, an AI model, at the single commit c726e4d on top of d739709, fetched
read-only into my clone. I read the whole diff (`ServerClient`, `WalletModel`, `Store`, `Engine`, `Tabs`,
`Parts`, the English and Czech texts, the stand-in's two new switches, `ServerSupport`, the new
`ServerClientTest` test) and ran only targeted tests, as asked: node `ServerClientTest` (13, all pass) and my
`aQuestionWaitsForTheLockLongerThanItsTime` (log `logs/third.log`). The full suite was not run.

### Item by item

| Item | Status | Remark |
|---|---|---|
| L3 | closed | `call` waits for its turn with a fair `ReentrantLock.tryLock(withinMs)` (`ServerClient.kt`, `call`), and a question that does not get its turn fails as "not answered in time" without being sent. My test now fails at its assertion: `validate(…, 1000 ms)` returned after 1,022 ms behind an 8 s question. The author's `aQuestionDoesNotWaitForItsTurnLongerThanItsTime` checks the same and also that the question was never sent. |
| S3 | closed | `once` ends a question exactly once (`AtomicInteger`: 1 by the reader or a failure, 2 by the clock), the clock disconnects only when it ended the question, and a reader that finds the clock first throws `Late`. I read every path (answer read, `TooLong`, `Late`, time-outs, I/O errors, other runtime errors, `finally`) and found no order of events in which the clock closes a connection whose answer was taken, or in which a late answer is used. No test, as the author says. |
| S2 | closed | The same txid refused again is kept once; with the list full the refusal says `REFUSED_NO_ROOM`, planning and confirming say `NO_ROOM`, and the open entry has its own text. The reason not to merge refusals that only share inputs is sound: each one pays what it says and could arrive. |
| S1 (a) | closed | The new screen test keeps the coin hidden and shows that every payment stays stopped until the owner forgets the entry; the texts tell the owner to look through another server or node first. |
| S1 (b) | closed, as far as text can close it | `Pending.waitsLong` (an hour, only for a payment whose time is known and that is not "outcome not known") shows a warning on the waiting entry and on the confirmation of the next payment. Both say what the code does: a payment made now does not stand against the waiting one. No "pay again in its place" action, which leaves the decision with the owner; acceptable. |
| S4 | closed | `paperRefusal` turns a refusal by a public node or a server into `PAPER_REFUSED`, with texts that say the transaction was seen and could still be passed on until the address is empty, and the words stay for a second attempt. The owner's own node keeps the plain refusal. |

### Did the change open anything?

- **The lock.** Fairness and `tryLock` with a timeout behave as described. A hand-over that stands behind the
  questions of a look now loses its check after 20 s and its later questions after what is left (at least 10 s
  each) without sending them; `deliver` then returns false, the payment is "outcome not known", and, never
  having left the phone, it becomes one that did not go through about a minute and two looks later, which the
  next payment stands against. Safe; with a slow server the owner may see "outcome not known" more often than
  before. Every other caller already treats a time-out as a failed look, plan or paper look. Nothing new.
- **The single ending in `once`.** See S3; nothing new.
- **The branch in `sendNow`.** Correct for the three cases (same txid kept already, list full, room left). One
  small thing (note): `outcomeOpen` gives `NO_ROOM` whenever the list is full and some payment is "outcome not
  known", also when that payment is in fact held by the node or server; then the next look does settle it
  (`settle` clears `unsure` for a held payment whatever the room), so "it cannot be settled for now" is
  briefly too strong. Harmless.
- **`waitsLong`.** A payment that comes back from the list of those that did not go through (`back`) gets the
  time of that look, so the hour starts again; a server that moves a payment back and forth could keep the
  warning from showing. Note only; it needs a lying server and the owner still sees "waiting".
- **Texts.** I read every new English text against the code and found none that promises what the code does
  not do, apart from the `NO_ROOM` nuance above. The Czech texts say the same as the English ones as far as I
  can judge them. `problem_paper_refused*` relies on the owner seeing the address empty through the same
  party that refused, which a lying party can falsify; the text already advises another node or server if the
  refusal repeats, which covers the case well enough.
- **Test support and stand-in.** `ServerNode` retries a node that did not start on another port and reports
  why; the stand-in's `refuse` and `hide` switches are off by default and do not change any other answer.

Nothing new of high or medium severity. Two notes (the `NO_ROOM` wording and the `waitsLong` reset).

### Not checked in the third look

The whole suite (not run, at the coordinator's request); `SixthLookScreenTest` and `AfterSixthLookPlainTest`
(neither read nor run by me); the author's 22 mutants; the S3 race on
Android's `HttpURLConnection`; the guides; anything on a device or emulator.
