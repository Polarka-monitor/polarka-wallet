
# Polarka Wallet 0.4.1 and 0.4.2 - third look of the independent reviewer

Status: see the last line of this file (FINAL only if it says so there).

Date: 14 November 2026, two sittings (04:12-04:25, cut short; 09:27-09:45). Scope: `~/north-build/north-wallet` at
9499ff8 (0.4.1, diff 5b490fb..9499ff8) and ddbcd76 (0.4.2, diff 9499ff8..ddbcd76), cloned to
`audit-0.4.1/polarka-wallet` and worked on only there; `~/north-build/polarka-node/gateway.py` at 7b7ba53 (0.1.2, diff
ba59d21..7b7ba53), read and run from its place.
Method: both diffs of the main sources read in full (Engine, WalletModel, Store, Planner, WalletCalls, Tabs, Parts,
MainActivity) and the English strings; `Engine.fetch` read again where the kept blocks matter; the changes to my own
tests read; the whole suite run once on each commit; three tests written (`Retest2ModelTest.kt`, in my clone only): two
fail by design (R2-01, R2-02), one checks the new safeguard with a node that is really stopped and started, and passes.
Not read: `review/` (also not the README you named: I worked from the claims in your message), the bodies of your
new tests (names and comments only), the Czech strings, the guides.
Signing: `app/build.gradle.kts:24-28` is unchanged; no task with "Release" in its name was run.

## Verdict

1. (a) The owner's small amounts with an own node: yes.
2. (b) A test build for other people, small amounts, through the project's own public node: yes. With an honest node I
   found no way left to pay twice for one thing, and no statement of the app that is untrue. The safeguard of 0.4.2
   held when I took a payment away from a real node by stopping it.
3. (c) Through public nodes the project does not run: only for people who know what that means. Such a node still cannot
   take coins or read the words. It can show blocks that do not exist (their work is not checked), and with that it can
   move the receiving address past what a restore looks at (R2-01), undo the safeguard for one payment (R2-03), and show
   a wrong balance and history. R2-01 is a small change and should go in before such nodes are suggested to anybody.
4. As before, nothing was found that lets another person or a node read the words or take coins.
5. Nothing that needs a phone was checked, and no package was inspected.

## What was claimed, and what I found

| id | state | where and how judged |
|---|---|---|
| R-01 | fixed | `Engine.kt:375`, `:387-411`, refusals `:465`; `gateway.py` answers 503 when the node's answer to sendrawtransaction is lost. My test of 0.4.0 passes: "the app says null, remembers 1 waiting payment, the shop holds 100". By reading: with one honest node a second try cannot be refused after a first try arrived (the node then returns the id, or -27, which counts as "on its way"). See R2-03 for several nodes behind one address. |
| R-02 | fixed | `Engine.kt:272` (steady), `WalletModel.kt:338-372`. My test passes (notice=null, the payment is in the block). What 0.4.1 left open ("gone" is said from what one node holds) is answered by the safeguard of 0.4.2, last row. |
| R-03 | partly fixed | `Engine.kt:154-161`, `:212-243`, `WalletModel.kt:395-398`, `:547-585`. A node can no longer move the key numbers past 39 (public) or 199 (own), nor be told more than 2 x 40 addresses, without the owner's yes: my test of 0.4.0 now prints "addresses named to it 80; receiving key number 39". Left: R2-01, with a failing test. |
| R-04 | fixed | `WalletModel.kt:160-169`: the clock that runs since the phone started; a clock that ran backwards locks. |
| R-05 | fixed | `WalletModel.kt:301-307`. By reading: no loop (the look that follows takes the engine and wallet of that moment), and the picture of the dropped look never reaches the screen. |
| R-06 | fixed (by text) | `MainActivity.kt:236`, string `offer_own_sees`: true and plain. The file form is unchanged, as you say. |
| N-3 | fixed | `WalletModel.kt:697-698` and the refusal path below it; your test prints "25 payments without a block in between; the change key number is 19". |
| N-4 | fixed | `WalletModel.kt:650-654`. |
| N-5 | fixed | `AuditSupport.kt:93`, `PublicNodeTest.kt`. In my runs nothing was skipped (TN_BIN and the gateway were there); I did not run without them. |
| 0.4.2 | sound with an honest node | `Store.kt:138`, `:159`, `:208`; `WalletModel.kt:338-425`, `:645-662`, `:737-742`; `Engine.kt:263-266`, `:319-360`; `Planner.kt:86-146`; `WalletCalls.kt:179`. My test with a real restart passes (text under "The places you trust least", a). Limits: R2-02, R2-03, note N2-1. |


## New findings (most severe first)

Nothing of medium or high severity was found. Three low findings and some notes.

### R2-01 (low) A public node can still move the receiving address past what a restore looks at, without a word

Where: `Engine.kt:212-213` (a fresh wallet starts with twenty keys), `:239-240` (widened to what is allowed when the
node shows one of them used), `:243` (the card "may have used more addresses" comes only when the first unused key
plus twenty lies beyond what was looked at), `WalletModel.kt:395-398` (the key numbers follow the node up to the limit).

What happens: a public node is told the first twenty addresses. If it shows one made-up block that pays the twentieth,
the app looks at forty, takes number 21 as its receiving address and writes that into the wallet's file. No card, no
notice: 20 + 20 is not more than 40. With more made-up payments the number goes up to 40, then with the card. A wallet
brought back from its words looks at the first twenty addresses and stops when none of them was used on the real
chain. Coins received on the address the app showed are then not on the screen until the owner finds "Look at twice as
many" on the More page. The coins are not lost. This is what is left of R-03 (F-03 before it).

Established: failing test `coinsOnTheAddressTheAppShowsAreFoundWhenTheWalletIsBroughtBack`: "after one look at a public
node that shows one made-up block: addresses named to it 80, the receiving address is number 21, card on the overview:
false; paid to that address at an honest node: 512.00000000; found by the wallet brought back from its words: 0.00000000".

Fix: let every look cover everything the app may look at by itself (40 keys with a public node, 200 with an own one),
not only twenty past the keys in use. Then no key number that a node can reach without the owner's yes lies outside
what a restored wallet looks at. The price is that a public node is always told 2 x 40 addresses, which a dishonest
one obtains today anyway.

### R2-02 (low) "An earlier payment ... wait for the next block" can stay for ever, and the way out is not named

Where: `Engine.kt:342-346` (no payment is planned while a payment that did not go through has no free coin),
`WalletModel.kt:380-391` (such a payment leaves the list only when a block spends one of its coins, when the node holds
it again, or when the owner forgets it), string `problem_earlier_payment`.

What happens: if the coins of a payment that did not go through no longer exist for the app, every payment is refused
with "That cannot be done at this moment. Refresh and work the payment out again; if it stays so, wait for the next
block", and no block ever changes that. Two ways there, both rare: a reorganization removed the coin for good (the
transaction that had created it was replaced); or the coin lies on a key the app no longer looks at (the address
numbers were worked out again, or the wallet moved from an own node to a public one). The only way out is "Forget this
payment" on the Overview page (or removing the wallet), and the message does not say so.

Established: the refusal that does not end is confirmed by the failing test
`aKeptPaymentWhoseCoinIsGoneDoesNotStopTheWalletAfterTheNextBlock`: "a kept payment whose coin the chain does not have:
can be spent 1 024.00000000; paying: EARLIER_PAYMENT; after the next block and a refresh, paying: EARLIER_PAYMENT; kept
payments: 1". The test writes that state into the wallet's file; I did not produce it by a real reorganization, and
the two ways into it are by reading.

Fix: name "Forget this payment" in the message, and say on the kept payment when none of its coins exists any more.
I would not let the app release it by itself: the coin can come back with the transaction that created it, so the
decision belongs to the owner, who only has to be told where it is.

### R2-03 (low, by reading; only for nodes the project does not run) What a node that does not show the truth can still do to the safeguard

Where: `WalletModel.kt:343-345`, `:383-384` (a payment is let go as "arrived" or "beaten" on blocks the node shows; their
work is not checked), `WalletModel.kt:706-715` (a payment the node refuses is struck out and not kept).

- A node that shows a made-up block in which another transaction spends a coin of a payment that did not go through
  makes the app let that payment go. The next payment is then not built on its coin. If the node (which holds the
  transaction) brings it into the real chain later, both arrive. Before 0.4.2 holding a payment back was enough for
  that; now it takes a made-up block, which costs such a node nothing.
- If one address stands for several nodes, a refusal (-25, -26) on the second or third try is not proof that nothing
  went out: the first try may have reached another of them. The payment is then struck out with no record, and the next
  one is free to take other coins. With the project's gateway (one node) this cannot happen.

Fix: check the work of the blocks used (as said for R-03); and when a refusal follows a try whose outcome was not
known, keep the payment as "did not go through" instead of striking it out.

### Notes

- N2-1 A waiting payment whose outcome is not known counts as standing against an earlier payment that did not go
  through (`Store.kt:159`), and its coins are kept aside. A third payment made in that minute stands against neither.
  The only guard is the text "Do not pay again now", as since 0.4.0. Stricter would be: no further payment while one is
  "outcome not known".
- N2-2 When one look finds a payment gone and another one arrived, only the first notice is shown (`WalletModel.kt:404-411`).
- N2-3 The owner's yes to look further (`lookTo`) is never taken back and goes along to another node: a wallet that
  looked at 5000 addresses with its own node names 2 x 5000 to a public node without a new question.
  "Work out the address numbers again" does not lower it.
- N2-4 The lists of waiting payments and of those that did not go through live only in the wallet's file. A wallet
  removed and brought back from its words starts without them, and the dialog for removing (`wallet_remove_text`)
  speaks of the words only. A payment that did not go through can then arrive beside a new one.
- N2-5 `deliver` can last about 90 s, not 40: inside one try NodeClient itself waits up to 45 s on 429 and 503.
- N2-6 "Work out the address numbers again" with a node that shows less than the truth (one that lags, for example)
  sets the numbers back, and coins on keys more than twenty past the last one that node shows are off the screen
  until the owner lets the app look further. With an honest node that is up to date it cannot hide coins or payments.
- N2-7 Lint: 0 errors here as well; the text report says 5 warnings and 2 hints (you named 11 warnings for 0.4.1).


## The places you trust least: what I found there

- (a) Two payments for the same thing in a block. With an honest node I found no way, by reading and by one test of my
  own, `afterTheNodeLostAPaymentTheNextOneCannotArriveBesideIt` (passes). The node took a payment and was then stopped
  and started with `-persistmempool=0`, so it came back without it, as after a crash: "notice=PAYMENT_GONE, kept as
  not gone through: 1, waiting: 0; paying again: problem=null, the new payment spends a coin of the first: true; the
  first one handed in again by a peer: refused; after a block the shop holds 100.00000000; the app then: kept: 0,
  waiting: 0". (In this test the three coins are equal, so the planner would have taken the same coin anyway; that the
  safeguard forces the coin when another would be preferred is what your own test shows, see (e).)
  Your list: an unsure waiting payment as an answer: N2-1. A draft made before the drop: stopped in `confirmSend`
  (`WalletModel.kt:655-661`). A drop while sending: a look that overlaps a send does not judge, so nothing is dropped
  then; a dropped payment that the node holds again in that moment goes back to the waiting ones, and the node refuses
  the new one as a conflict. Several coins: one coin of each is enough, they cannot both be spent. The limit of 50: the
  payment stays waiting and its coins stay tied, the safe side. A look that is not steady: the dropped part uses block
  data and getmempoolentry only, both safe to act on. The first hundred: later ones are not judged, the safe side. A
  node that lags: errs to the safe side (kept, told as arrived later). A node that lies: R2-03. Reorganizations: the
  last ten blocks are dropped from the kept ones and read again on every look, and everything is dropped when the
  highest older block is no longer on the chain, so "spent in a block" follows the chain; a payment let go as beaten
  still shares a coin with the transaction that beat it, whatever happens to that block.
- (b) Wedged for ever: R2-02. The safeguard hides no coins. "Forget this payment" is the only way out besides removing
  the wallet; it asks for the owner, and its text is honest about the price.
- (c) `settle()`. A dropped entry leaves on block data (arrived, beaten), on getmempoolentry (back to waiting) or by the
  owner; nothing else. A waiting payment leaves when it is in a block, when the node refused it at the hand-over, or
  when it is judged gone, and then it becomes a dropped one unless a block already decides. "Beaten" is sound with
  the kept blocks, see (a); in my test the kept payment was let go by the block that held the second one.
- (d) Planner with "must": sound by reading. The must coins come first and all of them are spent, also for a small
  amount; the fee follows the real number of coins; the rest returns as change; more than 1000 is refused; a coin named
  twice counts once. `PlannerMustTest` was run, not read.
- (e) Your new tests. I tried what you said: with `val must = against(...)` replaced by an empty list in my clone,
  `theNextPaymentIsBuiltOnACoinOfTheOneThatDidNotGoThrough` fails ("expected:<1> but was:<0>") and the other three
  pass; so does my own test above, which shows that it does not test that line. The line was put back (the file is
  identical to yours again). `NotGoneThroughTest` (four tests, names and comments read): the next payment is built
  on a coin of the dropped one although a larger coin is there; held again; arrived after all; a draft worked out
  earlier is not sent. Not covered by them: "beaten"; two dropped payments at once; the limit of 50; sending
  everything; the refusal from `against` when no coin is free; a look that is not steady.
  `AfterRetestTest` and `AfterRetestScreenTest` were run and their names read, their bodies not.
- (f) Left from 0.4.1. `deliver` under a node that restarts: sound (table, R-01); it can last longer than you say (N2-5).
  `allowed`/`windowOf`/`lookTo`: by reading no key in use and no change key can end up outside what is looked at, and
  the limit cannot grow by itself; a wallet moved from an own node to a public one names every key in use to it, and
  as many as the owner ever allowed (N2-3). `renumberKeys`: N2-6. The R-05 check: table. gateway.py 0.1.2: the 503 for
  a lost answer fits what the app does with it (it sends the same transaction again); `bucket_name` counts an IPv6 line
  by its /64 and reads the last entry of X-Forwarded-For, which is the one Caddy adds; deep nesting is refused with
  400. My probe of the rules: 14 shapes pass, 86 are refused, none unexpected; all 8 malformed requests are refused
  cleanly. The texts (`further_*`, `renumber_*`, `notice_look_limit`, `problem_unsure`, `pending_unsure_text`,
  `offer_own_sees`, and the new `notice_payment_*`, `dropped_*`, `send_against_text`): true and clear; the one I would
  change is `problem_earlier_payment` (R2-02).

## The lock test

I read your change to `AuditScreenTest.theLockEngagesAlsoWhenThePhoneRebuildsTheScreenOnReturn` and agree with it. The
wait was mine and it was weak: it watched the model and did nothing that makes the screen follow, and the unlocking
it waits for is started by the lock screen itself. That fits what I saw (two timeouts in six runs, under load, with
loaded and unlocked never reached). The change does not weaken the test: this wait only brings the app to "loaded and
unlocked" before the test proper, and everything the test claims (two minutes away, the screen rebuilt, locked, the
tabs not shown, the owner asked once) is asserted after it, unchanged. It passed in both of my suite runs (2.2 s).
I did not reproduce your second way to the timeout myself.

## Your claims about the tests

- 0.4.1 (9499ff8): 93 tests, 0 failures, 0 skipped; lint 0 errors. Confirmed.
- 0.4.2 (ddbcd76): 108 tests, 0 failures, 0 skipped; lint 0 errors. Confirmed.
- `RetestModelTest.kt` has the SHA-256 you named; I read it through and it is my text, and its five tests pass. (I
  did not compare it with the copy in `audit-0.4.0`, which I was told not to touch.)
- "With `val must = against(...)` replaced by an empty list, NotGoneThroughTest.theNextPayment... fails": confirmed
  (one run in my clone, then put back).

## What I could not check, or left out for time

- Anything on a phone or emulator; any package; `review/` (the emulator bench and the evidence files included).
- R2-03 and all notes are by reading; so are the two ways into the state of R2-02. Not tried: reorganizations, the
  limit of 50, many waiting payments, real concurrency of looks, sends and the background job, a gateway under load,
  the gateway's own test, the suite without TN_BIN or without the gateway.
- Not read: the bodies of your new tests, the Czech strings, the guides, Notify.kt and Scan.kt (not in the diffs).


## Test record

- run1, 04:12-04:17, 9499ff8 (0.4.1), everything with lint: 93 tests, 0 failures, 0 errors, 0 skipped; lint 0 errors.
- run2, 09:27-09:33, ddbcd76 (0.4.2), everything with lint, gateway set: 108 tests, 0 failures, 0 errors, 0 skipped;
  lint 0 errors, 5 warnings, 2 hints. My five tests of 0.4.0 pass; the lock test passes (2.2 s).
- run3, 09:34: my first two tests alone: the R2-01 test fails by design, the restart test passes.
- run4, 09:36: `NotGoneThroughTest` and my tests with `val must = against(...)` replaced by an empty list in
  `Engine.kt` of my clone: `theNextPaymentIsBuiltOnACoinOfTheOneThatDidNotGoThrough` fails, the other three and my
  restart test pass. Then `Engine.kt` was put back with git and compared with the copy taken before: identical.
- run5, 09:38 (last): my three tests on the unchanged sources: R2-01 and R2-02 fail by design with the texts quoted in
  the findings, the restart test passes.
- `tools/gateway_probe.py` against gateway.py 0.1.2: 14 shapes pass, 86 are refused, none unexpected; 8 of 8 malformed
  requests are refused cleanly (the deep nesting now with 400).
- The whole suite was run once on each commit, as asked; everything else was single classes.
- Left behind: nothing outside `audit-0.4.1/` (Gradle's own caches aside). No node or Gradle of mine is running; the tests' temporary folders are
  gone. Nothing was written next to gateway.py (the `__pycache__` there is from 03:28 and not mine). The clone is at
  ddbcd76 and `git status` shows one untracked file, `app/src/test/kotlin/org/polarka/wallet/Retest2ModelTest.kt`
  (three tests, SHA-256 05e285ca0231c02efaa0506c2f4051f9f6a8b4a44ef835b12754ea631d54c568).

## How to rerun

```
cd ~/north-build/audit-0.4.1/polarka-wallet && . ~/north-build/env.sh
gradle --offline --no-daemon -q --continue :app:testDebugUnitTest --tests "*Retest2*"      # the three tests of this look
sh ../tools/fails.sh Retest2                                                              # outcome and the lines they print
POLARKA_GATEWAY=$HOME/north-build/polarka-node/gateway.py gradle --offline --no-daemon -q --continue :core:test :node:test :app:testDebugUnitTest
sh ../tools/summ.sh
PYTHONDONTWRITEBYTECODE=1 python3 -B ../tools/gateway_probe.py ~/north-build/polarka-node/gateway.py
```

Gradle ends with "exit 1" while R2-01 and R2-02 are open: their two tests fail by design. With the test file in the
source tree the whole suite has 111 tests.

Status: FINAL
