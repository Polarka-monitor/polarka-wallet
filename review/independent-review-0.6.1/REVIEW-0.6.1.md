# Review of Polarka Wallet 0.6.1 (commits 96d78d5, 6fc25ea, d4a89e6 after 3b5e36a)

Reviewer: an AI model that had not seen this code being written. This is not a review by an independent
human expert. Date: 16 November 2026. Sources: the clone at d4a89e6 in
`~/north-build/audit-0.6.1/polarka-wallet`; the server's sources at 07523b6 (read only, not built or run).

## Verdict

I found nothing in 0.6.1 that lets an honest server make the wallet show an untrue line of history, and
nothing that weakens the safeguard against paying twice (H1, H2, M1 of the sixth look): `judge` changed in
one condition only, and the change matters only for exotic transactions (N2 below). The new reading of
pages, signed amounts and the server's picture number is careful and matches the server's sources. The
lines of several parts do add up exactly for an honest server (checked against `indexer/src/history.rs`).

What is weak is how the look behaves when the server's picture moves, and that is also where the author's
own account is not right. On the real server the picture number changes with every transaction that starts
to wait anywhere, the server answers 503 for a moment after each such change, and the history is read in
full on every look at about one second per page of 200 lines (the author's own probe: 4,000 lines in 20 s).
When a history or coin read restarts three times, the look does not "try three times" or show "a last look
that nothing is judged from", as `changes-0.6.1.md` says: it fails at once with an error and no figures,
because `ServerEngine.fetch` retries only `MOVED` (R1 below, shown by a test). At today's rate (one change in
about two minutes) that is rare; on a busier network, and for a wallet that is mined to, it will be common.

Would I hold the release back? **No, not for a safety reason.** 0.6.0 cannot read the public server at all,
and 0.6.1 fails safe: when it cannot get one picture it shows an error and judges nothing. I would hold it
for one day for the text: the sentence in `changes-0.6.1.md` about what happens on a busy network (and the
fallback message "blocks kept arriving") should say what the code does, and the release notes should name
the risk in one line. Fixing R1 in the code (retry the look on a 409 of a read, as on `MOVED`) is small and
I would do it before the release if the author can test it the same day; otherwise in the next version.

Findings: 0 critical, 0 high, 1 medium, 3 low, 4 notes.

## Findings

### R1 (medium). A moving picture makes the look fail outright, not "try three times"; and every look re-reads the whole history

Place: `app/src/main/kotlin/org/polarka/wallet/ServerEngine.kt`, `fetch` (lines 382-397) and `look`
(`histories(keys.parts)`, line 340); `node/src/main/kotlin/north/node/ServerClient.kt`, `utxos` and
`history` (`MAX_RESTARTS`, the `NodeError(409, null, ...)` at the end of each); `review/changes-0.6.1.md`,
"What was not done", fourth bullet.

Why it is wrong. `ServerClient.history` and `.utxos` read again from the start when the picture changes,
three readings at most, and then throw `NodeError(409, code = null)`. `ServerEngine.fetch` catches only
`ServerClient.MOVED` (which only `activity` throws); every other `NodeError` leaves `fetch` at once. So when
the server's number changes during each of three readings of one history (or coin list), the app gets no
snapshot at all and shows `Problem.Kind.NODE` with the client's words; the engine's own three tries are not
used. `changes-0.6.1.md` says instead that "the app then tries three times and says that the server kept
changing, or shows its last look as one that nothing is judged from". The fallback text in `fetch`, "blocks
kept arriving while the server was asked", is shown as BUSY and is also not what happens now that waiting
transactions move the picture.

How likely it is. From the server's sources: `pending_revision` goes up whenever the indexer publishes a
new picture (`indexer/src/pending_store.rs`), which it does after every block and every change of the node's
waiting transactions (`indexer/src/mempool.rs`, `refresh_mempool`); before publishing it marks the index
unavailable, so the server answers 503 for a moment (no `Retry-After`; the client then waits two seconds or
more). A history cursor holds for one revision (`indexer/src/history.rs`, `CursorChanged`). Every look reads
the whole history again, up to 2,000 lines per part, and the author's own probe on the public server
(`evidence-0.6.1/server-client.txt`) read 4,000 lines in 20.2 s, i.e. about one second per page of 200.
A wallet that is mined to reaches 2,000 lines quickly. At the rate the author measured (one change in about
two minutes) a ten-second history read is cut by a change about one time in twelve, and three in a row
rarely. At one change every twelve seconds (a modestly busy coin) a ten-second read is cut more often than
not, and the look fails roughly one time in five; a look of several parts almost never comes out steady.
Nothing is lost by that (the app judges nothing and shows an error), so this is availability, not safety.

How I showed it. `app/src/test/kotlin/org/polarka/wallet/ReviewerBusyTest.kt` (mine): a wallet with 201
lines of history (two pages) behind the stand-in; the relay changes `pending_revision` in every history
answer. Result: `fetch` throws `NodeError` 409 after six history requests and one coin request, i.e. after
a single look, and `Problem.of` gives `NODE`. Log: `logs/reviewer-app.log` (see "Tests I ran").

What would put it right. (1) In `fetch`, treat a 409 from `utxos` or `history` like `MOVED`: make the look
again, and return the last steady-or-not look as the comment promises. (2) Do not re-read confirmed history
on every look: keep the lines in blocks per tip hash and read only the newest pages until a known line (the
server's cursor is bound to the revision, so a long read cannot be made immune by the client alone; asking
the server's author for a history cursor of the lines in blocks that holds for the block only would help). (3) Correct the sentence in `changes-0.6.1.md` and the message in `fetch`, and say in the
release notes that a long history through a busy server may fail to load and why.

### R2 (low). Nothing bounds the number of pages of one read

Place: `ServerClient.utxos` (from line 362) and `ServerClient.history` (from line 449).

Why it is wrong. The only bounds are 20,000 coins and 2,000 lines per part; a page may hold one item. A
server that lists one coin per page with a cursor each time makes one read of coins take 20,001 requests,
and a read of history 2,000 requests per part; each request has its own 90-second limit but the read as a
whole has none, and with restarts it is three times that. `fetch` holds the engine's lock meanwhile and the
fifteen-minute background check does the same work. A hostile server could already stall a look, but now
it can do so while looking like an honest, slow one, at the cost of the phone's data and battery. The refusal
of an empty page with a cursor (which the brief asked about) is right: the real server never sends one
(`has_more` is set only after a full page, `indexer/src/utxos.rs`).

How I showed it. `node/src/test/kotlin/north/node/ReviewerPagesTest.kt` (mine) asserts the fault: 20,001
requests for one read of coins, 2,000 for one history; see "Tests I ran".

What would put it right. Bound the pages of one read (for coins, for example `MAX_COINS / 100` pages, since
the real server gives 1,000 to a page unless asked otherwise; or ask with an explicit `limit` and refuse a
page shorter than that unless it is the last), and give a look as a whole a deadline.

### R3 (low). The history of watched addresses drops movements without a word, for more reasons than the author names

Place: `ServerEngine.look`, line 364: `watchedMoves = ordered(movements(told))`; `Tabs.kt` line 405 (the
note looks at `historyWhole` only, which is made from the wallet's own lines).

Why it is wrong. `changes-0.6.1.md` names one case: a watched history longer than 2,000 lines per hundred
addresses ends without a note. The code drops watched movements silently in two more cases: a line that
`histories` marks as unsure (a part was cut short and may hold a line for it), and lines of two parts that
do not fit together (`movement` returns null). With more than a hundred watched addresses the first case is
the normal consequence of a long history. The Mining page counts rewards from `snapshot.watchedMoves` as well
(`Tabs.kt` line 442), so a cut-short watched history also gives a smaller count than is true; its note does
say that rewards further back are not counted.

Should it hold the release back? No. Watched addresses are shown, not spent from, and nothing is judged
from their history. It should be put right soon: give the snapshot a `watchedWhole` flag made as `whole` is
for the wallet, and show the same note above the watched lines.

How I showed it: by reading; the author's test `aPartOfTheAddressesWhoseHistoryIsCutShort` covers only the
wallet's own lines.

### R4 (low). Texts that say more than the code or the evidence

1. `review/changes-0.6.1.md`, "What was not done": "The app then tries three times and says that the server
   kept changing, or shows its last look as one that nothing is judged from." For a history or a coin list
   that keeps moving the app tries once and shows `NODE` with the client's words (R1). Where the engine does
   try three times (activity read across a change), the message is "blocks kept arriving while the server
   was asked", shown as BUSY, although waiting transactions now cause it as often as blocks.
2. `review/changes-0.6.1.md` and `release-texts/4-note-for-the-author-of-the-server.md`: the check that the
   lines of a whole history add up to what the coins hold was made on addresses with block rewards only
   (`evidence-0.6.1/server-sums.txt`: "0 gains, 0 losses" for all seven). It shows nothing about negative
   lines, fees, or several parts. The only negative lines compared with a node are four payouts of one
   address (`server-lines.txt`), each a single request. The claim that a history of more than a hundred
   addresses is exact rests on the server's sources and on regtest tests, which is sound, but the texts
   should not let the reader think the public server was checked for it. The note to the server's author
   calls them "seven early mining addresses"; `changes-0.6.1.md` calls them addresses that block rewards
   went to in October. One of the two is not right.
3. `release-texts/4-...`: "both start again on 409 or when `tip_hash` or `pending_revision` differ between
   pages". The client compares `indexed_height`, `indexed_hash` and `pending_revision` of the `index` object;
   `tip_hash` is a field of the cursor, which the client does not read. A small point, but this text goes to
   the person who knows the difference.
4. `release-texts/1-release-notes-v0.6.1.md` and `review/evidence-0.6.1`: "194 tests ... none skipped". In my
   run one test was skipped (`PublicNodeTest.aWalletWorksThroughThePublicGatewayAndItsAccountKeyStaysInThePhone`,
   no `gateway.py` on this machine). That is my machine, not a fault of the claim, but the claim holds only
   where the polarka-node gateway is present, and the README says so.
5. `problem_too_many_coins` (en, cs) prints the bound as "20000", without the thousands separator the other
   texts use. Cosmetic.

The rest of the texts I checked (below) say what the code does.

### N1 (note). Where the stand-in differs from the real server in a way that hides behaviour of the client

Ordering of pages (coins by height, id, number; history waiting first, then height down, then id), what a
cursor holds, when 409 is answered (only for a cursor whose tip hash or revision is not the current one;
never for a first page), the limits (1,000 coins a page by default, at most 10,000; history 1 to 200, 100 by
default), `net_amount` and `network_fee` for a block reward (fee null), for inputs of several owners (the
whole fee, net of own inputs), and after a reorganisation (tip hash changes, disconnected transactions wait
again) agree between `review/standin/northbound_standin.py` and `indexer/src/{utxos,history,queries}.rs` at
07523b6. The differences that matter for testing the client:

- The real indexer marks the index unavailable before it publishes a new picture of the waiting
  transactions or after a block (`refresh_mempool`, `mark_unavailable`), so the real server answers 503 for
  a moment after every change; it also answers 503 when its query slots are taken. The stand-in answers 503
  only when told to (`stale`). No test shows a look that meets a 503 in the middle and then a new revision,
  which is what a look on the real server meets after every block.
- The real server reads history at about a second per page (author's probe); the stand-in is fast. The
  duration of a look, which decides R1, is not tested anywhere.
- The stand-in raises its revision when it sees a change at request time; the real indexer when it
  publishes, every poll (two seconds by default). This does not change what the client may see.

### N2 (note). The test for "made elsewhere with the same words" changed

Place: `ServerEngine.judge`, line 279. 0.6.0 took a waiting transaction that is none of the wallet's own
for one made elsewhere when any of its lines had an input from the wallet's addresses; 0.6.1 when any line
is below zero. A transaction that spends a coin of the wallet and still leaves every part of the addresses
with more (it needs an input of somebody else, as in a pay-join) is no longer seen. The consequence is only
that a missing coin of an unanswered payment is not called tied up; by the H2 fix (as described in
`review/independent-review-0.6.0/README.md`; I did not re-read that code, which 0.6.1 did not change) the
wallet makes no payment while such a payment has no listed coin, so the safeguard holds. I mention it because the brief asked
whether 0.6.1 weakened anything about H1, H2 and M1: this is the only change to `judge`, and it does not.

### N3 (note). "On its way" can count change that is already being spent

Place: `ServerEngine.arriving`, line 245. The change of a waiting payment of the wallet's own is counted
from the note of what it put in. If another waiting transaction (made elsewhere with the same words, or by
other software) already spends that change, it is counted all the same, and the second transaction's own
change is not counted at all (its line is a loss and it is none of the wallet's payments). This is the
sixth look's N1 in a new form; the app itself builds only on coins in blocks, and the figure is shown, not
used. A crafted wallet file can make the figure as large as it likes, but nothing is decided from it.

### N4 (note). What a server gets from the picture number

A server that never changes the number gains nothing it did not have: the block hash is still compared,
and a server that lies can lie about everything. A server that changes it with every answer keeps every
look unsteady, so nothing is judged and waiting payments never settle; that is the same power as calling a
payment known for ever (sixth look S1(b)), and the Overview names a payment that has waited over an hour.
A server that leaves the number out is compared by the block alone, as 0.6.0 did; the real server always
sends it. An unsteady look is shown without any mark on the screen (as in 0.6.0, sixth look N4).

## What I checked and found sound

- Reading numbers (`ServerClient.wholeOf`, `.signed`, `.move`): text, fractions, exponents, more than 19
  digits, values past `MAX_SAT` and `Long` are refused; "-0" reads as 0, which is harmless. A block reward
  that takes coins away or waits for a block is refused. Depth must fit the block for coins and lines.
- Coins in pages: every page of every part must show the same height, hash and revision as the first; 409
  or a different picture throws away everything read and starts again (three readings); the same output twice
  is refused; an empty page with a cursor is refused (the real server never sends one); the cursor is a
  string of at most 1,024 characters sent back only to the server that gave it, inside JSON built by the
  library; memory is bounded by 20,000 coins plus one page of at most 10,000 and by 8 MB per answer. The
  stop at 20,000 is checked after each page, so a wallet with exactly 20,000 coins is read and one more
  coin is refused, as the texts say.
- History lines: the formula of `net_amount` and `network_fee` in `indexer/src/history.rs`
  (outputs to the requested scripts less own inputs; whole fee; null for a coinbase) makes the lines of
  disjoint parts add up exactly to what the transaction did to their union. `ServerEngine.movement` shows
  the sum, calls a line with no fee a block reward, and puts the fee beside a loss only when the loss is at
  least the fee. For an honest server I found no way to show change as income, a wrong amount, a payment as
  a reward or the reverse. The rule for a part cut short (`unsure`, including the tie at the floor height)
  is right and is tested (`aPartOfTheAddressesWhoseHistoryIsCutShort` catches the `<=` to `<` change).
- A lying server can make the history show any amount, any fee and any line as a reward or not, within
  the bounds of an amount; it can make notices tell a gain that is not there, inflate the count of rewards
  on the Mining page, and make "on its way" any figure. What is decided from history is what 0.6.0 already
  decided: a payment listed in a block counts as done (sixth look S1(c), named on the first screen), and a
  waiting line below zero that is none of the wallet's makes missing coins of unanswered payments count as
  tied up (more caution, not less). 0.6.1 adds nothing that a lie in the new lines can turn into a payment
  by the app. A false "on its way" could lead an owner to pay in the expectation of money that will not come;
  it is shown apart from what can be spent and cannot be spent, and a server could always show a false
  balance, which is the stronger lie. The same holds for a crafted wallet file (its "in").
- One picture: `ServerClient.moment` includes the revision; `ServerEngine.at` puts it into the set of
  pictures of the look, for the wallet and for watched addresses; `fetch` repeats a look that is not of one
  picture. (What happens when a single read cannot get one picture is R1.)
- What arrived (`since`): only movements whose summed net is above zero are told, so a payment of the
  wallet's own (a loss, change included) and a payment to itself (minus the fee) are not; the height filter
  by the lowest tip of all parts stops a line seen by only some parts from being told early or twice.
  A payment to a change key is now told, which closes the sixth look's N2.
- The wallet's file: "in" missing reads as 0, every other form that cannot be is refused; the note is kept
  through `Dropped` and back into `Pending` (`WalletModel` lines 424 and 429) and written when a payment is
  made (line 786). A payment made by 0.6.0 shows no change on its way and nothing worse.
- The safeguard against paying twice: `judge`, `held`, `back`, `tied`, `spentBy` are unchanged apart from
  N2; `SixthLookTest`, `ServerPayTest`, `ServerModelTest` and `SixthLookScreenTest` still exercise the same
  stories with the stand-in in the new shapes (their rewrites act on `utxos`, `transaction_id`,
  `/v1/transactions/broadcast` and status paths, which kept their names), and they pass.
- The 23 deliberate faults in `deliberate-faults.txt` are well chosen; I read the tests that are said to
  catch E7, E6, S1 and S2 and agree that they would.
- `docs/northbound-api.md` matches the server's sources, including the 503 while a new picture is taken
  and `GET /info` of the later commits (I read `git diff 07523b6 origin/HEAD`: only `/info`, documents and
  deployment files changed in the server).
- The texts of `strings.xml` (en and cs say the same), the README changes, `review/emulator/README.md`
  and the release drafts 1 to 3 say what the code does, apart from R4.

## Tests I ran

- The whole suite on d4a89e6: `logs/all.log`, summary `logs/all-summary.txt`: 194 tests, 0 failures,
  1 skipped (`PublicNodeTest`, no `gateway.py` on this machine).
- `node/src/test/kotlin/north/node/ReviewerPagesTest.kt` (R2): passes, i.e. the fault is there: 20,001
  requests for one read of coins (1.7 s against a local server), 2,000 for one history. `logs/reviewer-node.log`.
- `app/src/test/kotlin/org/polarka/wallet/ReviewerBusyTest.kt` (R1): passes, i.e. the look fails with
  `NodeError` 409 "the server's history kept changing while it was read", `Problem.Kind.NODE`, after one
  look (activity, coins, six history requests). `logs/reviewer-app.log`.
- One deliberate fault of my own: in `ServerEngine.histories` the rule for a part cut short, `one.height <=
  floor`, changed to `<`. `ServerEngineTest.aPartOfTheAddressesWhoseHistoryIsCutShort` failed, as it should
  (`logs/mutant-floor.log`). The source was put back; `git status` shows only my two test files.

Both of my tests assert the fault, as the earlier reviewer's did; they should fail once R1 and R2 are put
right. They are not part of the author's suite and I did not commit anything.

## What I did not check

- The build of the signed package, its checksum and its certificate; the emulator runs; anything on a phone.
- The server itself beyond reading its sources at 07523b6 (I could not contact it, by the rules).
- Code that 0.6.1 did not change (the H1/H2 fixes in `WalletModel.settle`, the planner, signing, `deliver`),
  except where the new lines flow into it.
- I ran one deliberate fault of my own (above) and did not repeat the author's 23; I judged them by reading
  the tests that are said to catch them.
- Lint, and the earlier reviewer's `ReviewerServerTest.kt`, which is not in the suite and was written for
  the earlier shapes.
- Performance on a phone of a look through the real server with a long history (R1 rests on the author's
  probe and on the sources).
