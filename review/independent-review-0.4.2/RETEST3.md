# Polarka Wallet at ed47dde - fourth look, short

Scope: `git diff ddbcd76..ed47dde` of the main sources in my clone `audit-0.4.1/polarka-wallet` (14 November, 10:11-10:24).
Engine, WalletModel and Store read in full; in MainActivity, Tabs and Parts only the places that use `lockNow`, `byHand`,
`asideSat`, `OUTCOME_OPEN`, `dropped_no_coin` and the dialog for removing; the new English strings. Look.kt was not read.
Signing: `app/build.gradle.kts:24-28` unchanged; no task with "Release" in its name was run.

## Verdict

1. Nothing in the two commits breaks what I found sound. R2-01 is closed (my test now passes), R2-02 is answered by
   text (rightly, I think), the second half of R2-03 and the notes N2-1, N2-3, N2-4 are done as you say.
2. One new low finding, opened by the new rule "no payment while one is not known": R3-01. Nothing else.
3. The verdicts of the third look stand: (a) yes, (b) yes, (c) with care; R2-01 no longer stands in the way of (c).

## Finding

### R3-01 (low) A clock that was ahead when a payment was written down can stop all payments until it catches up
Where: `WalletModel.kt:379` (a payment is judged only when it is a minute old by the phone's clock),
`:637-641`, `:682-685` (no payment is worked out or sent while a waiting one is "outcome not known").
What happens: a payment ends as "outcome not known" and never reached the node. If the phone's clock was ahead at
that moment and was put right afterwards, the payment stays "younger than a minute" until the clock passes the time
written down, so no look settles it, and since this version nothing can be paid meanwhile. There is no way out on
the screen but removing the wallet. Before, such a payment only kept its own coins aside. Rare: it needs a wrong clock.
Established: failing test `anUnknownPaymentWrittenDownWithAClockThatWasAheadDoesNotStopTheWallet` (`Retest3ModelTest.kt`,
my clone only): "waiting 1 (outcome not known: 1), kept 0; paying: OUTCOME_OPEN; after a block and two more looks,
paying: OUTCOME_OPEN". The test writes that state into the wallet's file (a payment written down two days ahead of now).
Fix: count a payment whose time lies in the future as old enough.

## Your six places

- (a) window = allowed: sound by reading. The new window is never smaller than the old one, so nothing that was
  looked at falls outside; keys in use, the change key and the next address stay inside (`Engine.kt:163-164`, `:171`).
  A restore covers every key a node of the same kind can lead the app to without the owner's yes; with real use
  beyond that, a used key lies within twenty of the edge and the card comes. Small: `acceptOffer` sets `lookTo` back
  also when the setup of the same node is saved again; the card then returns.
- (b) OUTCOME_OPEN: not for ever in ordinary use (held: cleared; in a block: done; neither: kept after a minute and
  two looks). The exceptions: R3-01, and a full list of 50 kept payments.
- (c) `lockNow`/`byHand`: not weakened. Only `unlockApp` after `owner.present()` opens; `byHand` only decides whether
  the phone's dialog comes by itself. During a send the lock is put off as before and then asks at once. The button
  is shown only where the app locks at all (`MainActivity.kt:126`).
- (d) `deliver`: "false" after a later no is the safe side. The payment stays "not known", the new rule keeps other
  payments back, and a look keeps it as "did not go through", so the next payment is built on its coin.
- (e) `asideSat`: display only; it brings the screens into line with what a payment can use. The coins set aside are
  part of those counted as spendable (nothing below zero, nothing twice) and show under "In payments waiting for a block".
- (f) The end of my third test: fair. It follows the fix I had named myself (tell the owner, do not release by
  itself), and the change is marked. Its name now says the opposite of what it checks; I would rename it.

## What I ran, and what I did not check

- One Gradle run of single classes at ed47dde, 10:18-10:22, after your suite had ended: Retest2ModelTest 3 pass (the
  R2-01 test prints "found by the wallet brought back from its words: 512.00000000"), RetestModelTest 5 pass,
  NotGoneThroughTest 5 pass, ThirdLookTest 2 pass, AuditScreenTest 4 pass (the lock test in 2.2 s), and my
  Retest3ModelTest: 1 test, fails by design. RetestModelTest.kt still has the SHA-256 80af88e4...7434.
- Rerun in the clone: `gradle --offline --no-daemon -q --continue :app:testDebugUnitTest --tests "*Retest3*"; sh ../tools/fails.sh Retest3`.
- Not checked: the new look itself and Look.kt; anything on a phone or emulator; any package; the whole suite, lint,
  core and node tests at ed47dde; the Czech strings; the bodies of ThirdLookTest and of your fifth NotGoneThroughTest;
  `review/` (also not the README beside my report).
- Left: clone at ed47dde with `Retest3ModelTest.kt` untracked; my earlier test in `audit-0.4.1/Retest2ModelTest.yours.kt`; no process of mine.

Status: FINAL
