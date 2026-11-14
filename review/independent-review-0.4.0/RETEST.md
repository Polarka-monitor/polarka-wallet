# Polarka Wallet 0.4.0 - retest after the independent review of 0.3.1

Status: FINAL (see the last line).

Date: 14 November 2026. Scope: the diff 3f9c400..5b490fb of `~/north-build/north-wallet` (cloned to
`audit-0.4.0/polarka-wallet`, worked on only there) and `~/north-build/polarka-node/gateway.py` (ba59d21, read only).
Method: the changed main sources read in full (WalletModel, Engine, NodeClient, WalletCalls, Setup, Store, Planner);
MainActivity, Parts, Tabs, Format, the manifest and the strings read as a diff; gateway.py and its deploy files read;
the suite re-run; five tests written (`RetestModelTest.kt`, in my copy only) against private regtest nodes: four
assert what a user may expect and fail on 0.4.0 (R-01 to R-04), one checks a property I found sound and passes.
Time spent: about 45 minutes, as asked. What that left out is listed under "What I could not check".
`review/independent-review-0.3.1/README.md` was read because the task named it; nothing else under `review/`.
Signing: `app/build.gradle.kts:24-28` opens the settings file only when a task name on the command line contains
"Release" (and tests `exists()` only then). The claim holds; no patch was made and no Release task was run.

## Verdict

1. (a) The owner's small amounts, own node: yes. 0.4.0 is clearly better than 0.3.1; ten of the twelve findings are fixed outright.
2. (b) Handing it to other people as a test build for small amounts through a public node: not quite yet. R-01 and R-02 are a few lines each, and both make the app tell a user that a payment failed when it went out, which is what 0.4.0 set out to end. With those two fixed: yes, through the project's own node.
3. R-03 should be fixed before anybody is pointed at a public node the project does not run: such a node can undo the privacy of the public mode and plant a later "restore does not find my coins".
4. As before, nothing was found that lets another person or a node read the words or take coins.
5. Nothing that needs a phone was checked, and no package of 0.4.0 was inspected.

## The twelve findings of 0.3.1

| id | state | where and how judged |
|---|---|---|
| F-01 | fixed | Waiting payments are in `wallet.bin` before delivery (`WalletModel.kt:540-546`); `Engine.plan` asks `gettxout(include_mempool)` (`Engine.kt:272-285`); `singleTask`. My test passes. Left open: only the first 100 chosen coins are asked (`:281`), see N-2. |
| F-02 | partly fixed | A lost answer (no connection, time-out, 429, 503) is handled well (`Engine.kt:312-330`); my test passes, its adaptation is fair. An answer from a gateway or proxy that did not hear the node is still taken for a refusal: R-01, with a failing test. |
| F-03 | fixed for an honest node | `Engine.kt:183-207`, `WalletModel.kt:315-320`, `:445-449`; my test passes. The search believes whatever blocks the node shows: R-03, with a failing test. |
| F-04 | fixed | `WalletModel.kt:140-149`, `MainActivity.kt:67,76,81`; removing the wallet or the setup and switching the lock off ask `owner.present()`; `lock()` drops paper words. Adaptation fair. Left open: the clock, R-04. |
| F-05 | fixed | `PaperDraft` carries both addresses, the confirmation prints them (`Tabs.kt:562-565`), any edit drops the draft (`WalletModel.kt:613`). Adaptation fair, and it now checks what the confirmation shows. |
| F-06 | fixed | `Tabs.kt:396`. |
| F-07 | fixed | Stores read one by one (`WalletModel.kt:113-123`); stored words are never written over (`:370`); `rebuildWallet` (`:390`); files synced before the rename (`Store.kt:56-63`). |
| F-08 | fixed | `Tabs.kt:567-571`, strings 187, 241, 242. |
| F-09 | fixed | `NodeClient.kt:254-310` (amount and sum bounds, ids, duplicates, 16 MiB, `clean`). One consequence of the size limit: N-1. |
| F-10 | fixed | The id is `signed.txid`; `deliver` compares the node's answer with it (`Engine.kt:315`). |
| F-11 | fixed | `WalletModel.kt:455-463`, `Format.value` takes plain numbers only and cannot throw. |
| F-12 | fixed | `Planner.sweep/split` take the 1000 largest and report `leftBehind`; the paper screen looks again; the Send tab says so. |

The five adapted tests: all five still test the finding honestly (compared line by line with my originals). The other 19 are unchanged.

## New findings (most severe first)

Each "confirmed by test" names a test in `RetestModelTest.kt` that asserts what a user may expect and FAILS on 0.4.0.

### R-01 (medium) A gateway or proxy that did not hear the node is taken for a refusal; paying again pays twice, at once

Where: `Engine.kt:313-325` (only "no connection", 429 and 503 count as "not known"; `else -> throw e`),
`WalletModel.kt:552-555` ("it is certain that it did not go out": the waiting payment is struck out),
`NodeClient.kt:162-180` (any other status with or without JSON becomes an ordinary error),
`gateway.py:303-307` (answers HTTP 502 with a JSON error whenever its own call to the node raised).

What happens: the node takes the transaction, but what stands in front of it reports a failure: gateway.py when its
connection to the node timed out or broke, Caddy when the gateway is restarting, any reverse proxy in front of an own
node (502, 504). The app shows "The node answered with an error", removes the waiting payment and keeps nothing.
Because `plan()` now steps around coins that the mempool spends, the retry does not collide with the first payment as
it did in 0.3.1: it takes other coins and goes out immediately.

Established: confirmed by test `aGatewayThatDidNotHearTheNodeIsNotTakenForARefusal` (relay passes the call on and then
answers what gateway.py answers): "the app says NODE (the node behind the gateway did not answer), remembers 0 waiting
payment(s), the node's mempool holds 1; paying again at once: problem=null; the shop now holds 200.00000000".

Fix: count as a refusal only what the node itself decided (a JSON-RPC error with the node's own code, such as -25, -26,
-22); for everything else (5xx, the gateway's -32603, an answer that is not JSON or not in the expected form) ask
`getmempoolentry` and keep the payment as "outcome not known". In gateway.py answer a failed upstream call to
`sendrawtransaction` only after asking the node whether it holds the transaction, or with a status the app treats as
unknown.

### R-02 (medium) A payment that enters a block while the app is looking is reported as "did not go through"

Where: `Engine.kt:183-225` (history, then unspent coins, then `getmempoolentry`, one after another),
`WalletModel.kt:298-312` (not in the history read earlier, not held, coins still in the list read earlier: "gone").

What happens: a block found between the reading of the unspent coins and the question about the waiting payment makes
a payment that has just been confirmed look gone. The app shows "A payment of ... is no longer held by the node and is
in no block. It did not go through", forgets it, and the history on the screen (read before the block) does not show
it either. A user who believes the notice pays again. The window is the time the look spends between those calls: short
with a fast node, many seconds when a public node makes the app wait (429, Retry-After). By reading, the same judgment
falls on a node that has just restarted and is still loading its waiting transactions.

Established: confirmed by test `aPaymentThatEntersABlockDuringALookIsNotReportedAsGone` (a block is mined when the app
asks `getmempoolentry`): "notice=PAYMENT_GONE (100.00000000), waiting payments now 0; the shop holds 100.00000000 in a block".

Fix: the look already asks `gettxout` for the coins of each waiting payment (`Engine.kt:218-225`): a payment whose
coins are spent by that answer is not gone. Also compare the best block before and after the look and do not judge if it
moved; call a payment gone only on the second look that says so.

### R-03 (medium) A node can move the wallet's key numbers thousands ahead and make it name 10,000 addresses

Where: `Engine.kt:183-207` (the window is widened from blocks the node names; nothing about a block is checked beyond
its form), `Engine.kt:131-133` (a public node is told every address of the window), `WalletModel.kt:315-320` (the key
numbers found are written into the wallet's file), `:445-449` (no way back; "Next address" then refuses).

What happens: a public node is told the next twenty addresses of each branch. If it answers with a block that pays the
last ones it was told (a made-up block costs it nothing), the app takes them for used, doubles its window and names
more, up to 5000 per branch. After one look the node knows 10,000 addresses of the wallet, which is what the public mode
was built to prevent, and the wallet's receiving address is number 5001 for good. Every later look asks about 10,000
addresses (40 scans of each kind). And coins received from then on lie 5000 keys beyond the last really used one: a
wallet brought back from its words stops after twenty unused keys and does not find them (F-03 again, by reading).

Established: confirmed by test `aPublicNodeCannotPushTheKeyNumbersFarAhead` (stand-in public node): "addresses named
to it 10000; receiving key number 5000, change key number 5000, window 5020; stored in the wallet's file: 5000".

Fix: do not let unverified data move stored key numbers without limit: a small step per look, the owner's consent
beyond (say) 100 keys, a much lower ceiling for a public node, and a way to work the key numbers out afresh (at least
when the node is changed). Checking the proof of work of the blocks used would close it properly.

### R-04 (low) The minute away is measured with the clock anybody can set

Where: `WalletModel.kt:140-149` (`System.currentTimeMillis()`). Setting the phone's clock back before reopening the
app keeps it unlocked. Confirmed by test `settingTheClockBackDoesNotKeepTheWalletOpen`. Fix: `SystemClock.elapsedRealtime()`.

### R-05 (low, by reading) A look made for one wallet can be settled into another

Where: `WalletModel.kt:264-290`, `:298-320`. The look takes the wallet at its start; `settle` applies its key numbers
and its picture to whatever wallet is in the state when it ends. Removing a wallet and bringing another back while a
slow look is under way moves the new wallet's key numbers to the old wallet's. Fix: ignore a look whose account key
is not the current one.

### R-06 (low, by reading) Which kind of node it is follows from whether the setup has a password

Where: `Setup.kt:33` (`isPublic = user.isEmpty()`), `Engine.kt:131-133`. A setup file with a user name and password is
treated as a node of one's own and is given the two descriptors with the account key. An operator who hands out such a
file for a shared node gets the key that shows the whole wallet; "Check before saving" does not say so. Fix: say on that
screen what the node will see, or make the kind an explicit field of the file.

### Notes

- N-1 (by arithmetic, not tested) `getblock` with all transactions grows to about 3.3 bytes of JSON per weight unit for
  ordinary traffic (13 MB for a full block) and to 4 or more for witness-heavy shapes. The app refuses answers above
  16 MiB (`NodeClient.kt:254`), the gateway passes up to 48 MB. One such block that touches the wallet would make every
  look fail. Not reachable with today's blocks. A look should survive one block it cannot read.
- N-2 `plan()` asks the node about at most the first 100 chosen coins, `paperLook` not at all above 100 coins
  (`Engine.kt:281`, `:350`); larger payments are not protected against a payment made on another phone.
- N-3 The change key number moves on when a payment is written down and is not taken back when the node refuses it or
  it is later found gone (`WalletModel.kt:545`, `:552-555`). Twenty such in a row would put change beyond a restore.
- N-4 More than 200 waiting payments can be saved but not read back (`Store.kt`, MAX_PENDING); the way out is "rebuild".
- N-5 `PublicNodeTest` prints "SKIPPED" and counts as passed when gateway.py is not found next to the repository. In my
  clone it did exactly that until POLARKA_GATEWAY was set. "80 passed, none skipped" is true only in the author's layout.
- N-6 A task name abbreviated so that it does not contain "Release" would build an unsigned release rather than open
  the signing settings: harmless, but the check is by name.

## The places you trust least: what I found there

- (a) Life of a waiting payment. Sound by reading: written down before delivery; a look that overlaps a send does
  not judge (`sends` is raised before and after, and `state.sending` is checked: every overlap I could construct gives
  `judge = false`); a failure to write stops the payment. The app dying between `persist()` and `deliver()`: confirmed
  by test `aPaymentWrittenDownButNeverHandedOverIsClearedUpOnTheNextStart` (passes): on the next start the owner is
  told, the coin is free and can be paid with. Holes: R-01 (struck out although it went out) and R-02 (reported gone
  although confirmed). The reverse (shown as waiting although it is not) lasts only until the next look.
- (b) `Engine.deliver`. With an honest node it says "sent" only when the node returned the id, holds the transaction,
  or names it as already known; handing the same transaction over again cannot pay twice. The hole is what it throws: R-01.
- (c) The widening loop. It ends (the window at least doubles, ceiling 5000, at most nine rounds). A node cannot make
  it run for ever, but it can make it expensive and move the key numbers: R-03. For an honest public node a look costs
  one `scanblocks` and one `scantxoutset` per 250 addresses; a restore of a much-used wallet repeats that per round.
- (d) Public mode. The account key and the descriptors do not reach a public node (PublicNodeTest, run against the
  real gateway: passes; `reach()` is the only place that names keys). No Authorization header without an account
  (`NodeClient.kt:121`). The node does learn the window of addresses, the watched addresses, and the forty addresses
  of a paper account that is looked at, all tied together by one connection; the text on the screen says so fairly.
  What a hostile public node can do that it could not do to 0.3.1: R-03; R-06 if it hands out a setup with an account;
  and it can hold a look up for 45 s per call with 429.
- (e) `lock()`, `lockWanted`. Sound by reading: a lock asked for during a send or a paper action is made up for when
  it ends, on every path I followed. Hole: R-04.
- (f) `Setup.parse`. Sound by reading and the existing tests: both forms take only a plain https address; an account
  is either complete or absent; without an account only in the second form. See R-06 for what follows from it.
- (g) gateway.py. Sound: an explicit list of calls; positional parameters only; exact shapes; the request is written
  anew before it goes to the node, so the node sees only what was checked; no ranges, no abort, one transaction per
  call. `tools/gateway_probe.py` tried 86 shapes that must be refused (other calls, ranges, extra parameters, wrong
  types, batches, named parameters, doubled keys): all refused; 14 shapes the wallet uses: all pass. Weak points, by
  reading: R-01 (502 after a failed call to the node); limits are per full address, so a /64 of IPv6 or a few hosts can
  keep the single scan slot busy; answers up to 48 MB are read and parsed whole, 64 at a time, under MemoryMax=512M
  (only a concern once blocks are large); a deeply nested request ends as 502 "the node did not answer" (wrong
  status, harmless). I did not run the gateway's own test, and nothing was tried under load.

## What I could not check, or left out for time

- Anything on a phone or emulator; no package of 0.4.0 was given, so nothing was compared with the sources this time.
- R-03's last step (coins beyond the gap are not found after a restore), R-05, R-06 and N-1 to N-4 are by reading.
- Not read: Notify.kt and Scan.kt beyond their diff (renames only), the Czech strings, README, THIRD-PARTY, the
  author's FixesModelTest/FixesPlainTest/HardeningTest (run, not read), `review/` other than the one README.
- Not tried: many waiting payments at once; real concurrency of refresh, send and the background job; long
  reorganizations against the new anchor check; a storm of 429; the gateway's rate limits in practice.

## Your claims about the tests

- "All 80 tests pass": yes in my first full run (run1). "None skipped": not in my clone. `PublicNodeTest` did not
  find gateway.py, printed SKIPPED and was counted as passed (N-5). With POLARKA_GATEWAY set it runs against the real
  gateway and passes (165 requests).
- One test of the suite is not stable here: `AuditScreenTest.theLockEngagesAlsoWhenThePhoneRebuildsTheScreenOnReturn`
  timed out at its first wait (`AuditScreenTest.kt:90`, the app loading and unlocking, before the test proper) in
  two of the six runs that executed it (run3, everything with lint, and run4, with my tests; 30 s each, then
  ComposeTimeoutException). It passed in the other four (run1 and run7 with everything, run5 alone, run6 with its
  class), in about two seconds. My tests cannot have caused it: they run after it. I could not find the cause in the
  time (a print added for one run did not catch a failure, and was taken out again); the computer was shared, so load
  is possible, but the tests around it ran at normal speed. Worth a look: if the cause is in the app, it is in loading
  or unlocking.
- "Lint 0 errors": yes (5 warnings, 2 hints).
- The five adapted tests are fair (see the table above).

## Test record

- run1 02:17-02:21: the suite as cloned, 80 tests, 0 failures (PublicNodeTest self-skipped).
- run2: my four tests and PublicNodeTest with the gateway: PublicNodeTest passes; my four fail by design.
- run3 02:30-02:35: everything with lint: 84 tests; lint 0 errors; my R-03 and R-04 and R-02 fail by design; my R-01 failed
  for a reason of my own (the test asked the node for a scan while the app's own look was scanning; corrected afterwards);
  the lock test above timed out.
- run4: my tests (now five: four findings and one that is expected to pass) and AuditScreenTest: my four fail by
  design with the texts quoted in the findings, the fifth passes; the lock test timed out again.
- run5, run6: the lock test alone, then AuditScreenTest alone: all pass.
- run7 02:43-02:47 (last, everything, gateway set): 85 tests (the 80 of the suite and my five), 4 failures, 0 errors,
  0 skipped. The four failures are R-01 to R-04, by design. All 80 of the suite pass, the lock test (2.2 s) and
  PublicNodeTest against the real gateway among them. The 36 tests of `core` and `node` were not executed again after
  run1 (nothing they depend on changed, Gradle kept their results); the 49 tests of `app` were executed.
- `tools/gateway_probe.py`: 14 shapes pass, 86 refused, none unexpected; of 8 malformed requests 7 are refused cleanly
  and one (deep nesting) ends as 502.
- No node or gateway of mine is left running (checked after run7; the nodes and gateways still running belong to the
  emulator runs under `emu/`, which I did not touch). Written outside `audit-0.4.0/`: nothing that remains (Gradle's
  own caches aside; the tests' temporary folders in the system's temporary folder were removed by the tests). gateway.py
  was read and executed from its place; no byte code was written next to it. The clone's `git status` shows one
  untracked file, the test file named below; HEAD is 5b490fb.

## How to rerun

```
cd ~/north-build/audit-0.4.0/polarka-wallet && . ~/north-build/env.sh
POLARKA_GATEWAY=$HOME/north-build/polarka-node/gateway.py gradle --offline --no-daemon -q --continue :core:test :node:test :app:testDebugUnitTest
gradle --offline --no-daemon -q --continue :app:testDebugUnitTest --tests "*Retest*"     # only the retest
sh ../tools/summ.sh; sh ../tools/fails.sh Retest
PYTHONDONTWRITEBYTECODE=1 python3 -B ../tools/gateway_probe.py ~/north-build/polarka-node/gateway.py
```

Gradle ends with "exit 1" because the four tests R-01 to R-04 fail by design. The only file added to the clone is
`app/src/test/kotlin/org/polarka/wallet/RetestModelTest.kt` (five tests).

Status: FINAL
