# North Wallet 0.3.1 - independent security audit

Status: FINAL (see the last line).

Date: 14 November 2026. Scope: the sources in `audit-0.3.1/north-wallet` (modules core, node, app; about 3,100 lines)
and the signed package `north-wallet-0.3.1.apk` (SHA-256 18ae9809...6bf1e7, verified).
Method: every main source file and every existing test read once in full; the existing suite re-run; 24 own tests
written (JVM, Robolectric with the real activity, and a private regtest node started from TN_BIN); static inspection
of the APK. No earlier review, journal or STATE file was read. No real node, no real coin, no signing key was touched.
`ReviewTest.kt` was read because it is one of the test files inside the sources.

Change to the audit copy: one line of `app/build.gradle.kts` was changed so that Gradle never opens
`~/.config/north-wallet/signing.properties` (the original is kept as `audit-0.3.1/app-build.gradle.kts.orig`).
No main source file was changed. The added files are listed at the end.

## Verdict

1. (a) The author's own small amounts: acceptable, if three traps are avoided until fixed: never edit a field after "Check the payment" on the paper screen (F-05); after an error while sending or after the app was closed, look at History before paying again (F-01, F-02); after bringing a wallet back the balance can be missing (F-03).
2. (b) Release to other people: no. Must be fixed first: F-05, F-03, F-02, F-01, F-04, F-08; F-06 and F-07 are small and should go with them.
3. No way was found for another person, or for the node, to read the words or to take coins; keys, addresses, signing and fee arithmetic are sound.
4. Each of the 12 findings has a failing test here that becomes a regression test once it is fixed; the 41 existing tests still pass.
5. Nothing that needs a phone was checked (Keystore, fingerprint dialog, TLS, keyboards, autofill); test on a device before any release.

## Findings (most severe first)

Twelve findings: 2 high, 4 medium, 6 low. Each "confirmed by test" names a test that asserts what a user is entitled
to expect and FAILS on 0.3.1; the line printed by the test is quoted.

### F-05 (high) The confirmation for emptying a paper address shows other addresses than the transaction pays

Where: `app/.../Tabs.kt:505` (`val to = mine ?: other`), `:526` (`PlanFields(plan, to, ...)`), `:529` (`Mono(restTo)`);
the fields at `:510`, `:513`, `:517` stay editable after "Check the payment"; `WalletModel.kt:368` keeps only the plan.

What happens: the plan is made from the fields at the moment of "Check the payment", but the confirmation below prints
the recipient (typed only when the phone has no wallet of its own; that case by reading) and the address for the rest from the text fields as they are now. The fields are not locked, and there is
no other way to correct a mistake (Cancel throws the 24 typed words away), so a user who notices a wrong address in the
confirmation corrects the field. The confirmation immediately shows the corrected address, the amounts stay, and "Sign
and send" pays the first address. This is the screen on which the app tells the user to compare addresses character by
character, and it is used for the largest amounts (the app's own text: larger savings belong on paper). A clipboard that
held a foreign address (by accident or by malware) is the realistic trigger. The Send tab does not have this defect: it
prints `draft.to`.

Established: confirmed by test `AuditScreenTest.thePaperConfirmationShowsTheAddressThatWillBePaid` (real screens, regtest):
"when 'Sign and send' was tapped the screen showed the corrected address in 2 place(s) and the first one in 0; the
corrected address received 0.00000000, the first one 1 525.99999408".

Fix: keep both addresses in the draft and print the confirmation from the draft only (as the Send tab does); while a
draft exists make the fields read-only or drop the draft on any edit; let Cancel go back to editing without discarding
the words. Add the test above to the suite.

### F-03 (high) A wallet brought back from its words does not find coins beyond key 19 and can show zero

Where: `WalletModel.kt:252` (`WalletInfo(chain, xpub)`: both key numbers start at 0), `Store.kt:116` and `:132`
(window = highest number used so far + 20), `Engine.kt:98-106` (only that window is asked for). No code looks further.

What happens: every payment with change moves the change to the next change key, so after 21 payments the money of the
wallet sits on change key 20 or higher; the same holds after 21 taps on "Next address". The phone that made the wallet
knows its numbers. A phone on which the wallet is brought back starts at 0 with a window of 20, never extends it, and
shows whatever happens to lie on keys 0 to 19, with no hint that more may exist. For a wallet in normal use that is
zero. The coins are not lost (tapping "Next address" widens the window, and TrueNorth Core finds them with the
descriptor), but the user is not told, and restoring is exactly the moment when the app must be right.

Established: confirmed by test `AuditModelTest.aWalletBroughtBackFromItsWordsShowsAllItsCoins` (21 real payments on
regtest, then restore into empty stores): "before the loss the wallet showed 490.99996577 (change key number 21 in
use); brought back from its words it shows 0.00000000 with window 20".

Fix: after restore (and cheaply at every refresh) search until 20 unused keys follow the last used one on both branches:
`scanblocks` with a wide range finds used keys even when they are empty now; set both key numbers from the result. Until
then, at least say on the restore screen that only the first 20 addresses are searched.

### F-02 (medium) When the node's answer to "send" is lost, the app says nothing was sent, and the retry pays twice

Where: `Engine.kt:181-188` (`send`: the transaction id exists before `node.broadcast`, but nothing is kept),
`WalletModel.kt:317-342` (a waiting payment is recorded only on success; the failure path at `:340` records nothing),
`NodeClient.kt:92-93` (any I/O error becomes "cannot be reached"), `strings.xml:87` and `:205`.

What happens: the node accepts the transaction and the connection drops before the answer arrives (mobile network,
Tailscale path change, the 30 s timeout). The app shows "The node cannot be reached...", shows no waiting payment, and
keeps the confirmation open. A second tap is answered with "The node refused the transaction
(txn-same-nonwitness-data-in-mempool). Nothing was sent." - which is false. Once the first transaction is in a block
its coins are gone from the node's list, so a new attempt is built from other coins and goes out as a second payment.

Established: confirmed by test `AuditModelTest.aLostAnswerDoesNotEndInPayingTwice` (a relay between app and regtest node
drops the answer of `sendrawtransaction`): "the app says UNREACHABLE, remembers 0 waiting payment(s), the node's mempool
holds 1; second tap: REJECTED txn-same-nonwitness-data-in-mempool; new payment after a block: problem=null; the shop
now holds 200.00000000" (100 was wanted).

Fix: take the id from the signed transaction and record the payment (id, inputs, raw hex) as "outcome unknown" BEFORE
`sendrawtransaction`, persistently. After an I/O error ask `getrawtransaction`/`getmempoolentry` for that id, or send the
identical hex again (the node answers with the same id while it is in the mempool), and only then report. Treat the
reasons "already in mempool / same nonwitness data / already known" as "this payment is already on its way".

### F-01 (medium) Waiting payments are forgotten when the app is closed; the next payment silently cancels the earlier one

Where: `WalletModel.kt:45`, `:69` (the list lives only in the screen state), `:303` (`used`), `Engine.kt:175`;
`core/.../Spend.kt:16` (every input signals that it may be replaced); `AndroidManifest.xml:29-37` with `Notify.kt:69`
(a tap on a notification can open a second activity with its own, empty list; by reading, not tried on a phone).

What happens: until its block arrives, a payment is known only to this list; the node (`scantxoutset`) still reports its
coins as unspent. After the process ends (swiped away, stopped by Android, phone restarted) the list is empty, the
overview shows the old balance without the "Sent, waiting for a block" card, and the next payment is built on the same
coin. If its fee is higher, the node accepts it as a replacement and drops the first payment, which the app had
reported as "Sent". If its fee is not higher, the node should refuse it instead (that branch was not tested).
Within one run of the app the list works (test `inOneRunOfTheAppAWaitingPaymentIsLeftAlone` passes).

Established: confirmed by test `AuditModelTest.aPaymentMadeAfterTheAppWasClosedDoesNotCancelTheOneBefore`: "after
reopening the app remembers 0 waiting payment(s); second payment: problem=null; mempool after the second: [only the
second id]; after one block the shop holds 0.00000000, the friend 50.00000000".

Fix: store waiting payments in `wallet.bin`; before planning, ask the node whether each candidate coin is spent in the
mempool (`gettxout` with `include_mempool=true` is already on the node's list of allowed calls) and leave such coins
out; never replace an own payment without saying so; give the activity `launchMode="singleTask"`.

### F-04 (medium) The lock is skipped when Android rebuilds the screen on return

Where: `MainActivity.kt:58` (`private var leftAt = 0L`), `:70-78` (set in `onStop`, read in `onStart`).

What happens: the moment the app went to the background is kept in the activity object. If the configuration changed
while it was in the background (phone turned with a rotating launcher, folded or unfolded, dark mode switched on by
schedule, font or display size changed), Android destroys the stopped activity and creates a new one on return. The new
object has `leftAt == 0`, so `model.lock()` is not called, while the model survives the rebuild and is still unlocked.
Whoever holds the unlocked phone then sees balances, addresses and history, can copy the public descriptor (a lasting view of the whole wallet), and can remove the wallet, remove the node
setup or switch the lock off (none of these asks for the fingerprint; uninstalling the app would destroy the words as well). Sending still needs the fingerprint. By reading: the lock is also skipped, and not made up for later, when a send or a paper action is in progress at that moment (`WalletModel.kt:116`).

Established: confirmed by test `AuditScreenTest.theLockEngagesAlsoWhenThePhoneRebuildsTheScreenOnReturn` (Robolectric;
the control case in the same test locks as intended): "back after two minutes and a rebuild of the activity:
locked=false, the tabs are on the screen=true, the owner was asked 0 time(s)". Not yet reproduced on a phone; steps:
unlock, press Home, wait over a minute, switch dark mode in the quick settings, open the app.

Fix: keep the time in the model (it survives the rebuild) or use `ProcessLifecycleOwner`; ask the owner
(`owner.present()`) before removing the wallet, removing the setup or switching the lock off.

### F-08 (medium) "This empties the paper address completely" is said while block rewards stay behind

Where: `strings.xml:194` (and the Czech text), `:189` ("After that the paper is no longer safe: move everything"),
`Tabs.kt:532`, `Engine.kt:74` and `:202-207` (only ripe coins are planned).

What happens: a paper address that is still mined to usually holds rewards younger than 100 blocks. They are not moved,
yet the confirmation says the address is emptied completely, and the words have just been typed into a phone. A user who
believes the text and destroys the paper loses those rewards; one who keeps it holds coins on words the app itself
calls no longer safe. The amount is shown higher up as "Rewards still maturing", but the red warning contradicts it.

Established: confirmed by test `AuditScreenTest.theWarningDoesNotSayEverythingIsMovedWhenRewardsStayBehind`: "the
confirmation said 'This empties the paper address completely': true; after the transaction the paper address still
holds 512.00000000".

Fix: when anything stays behind, say how much, when it ripens, and that the paper must be kept and emptied again.

### F-07 (low) One file that cannot be read hides the wallet, and "Make a new wallet" then overwrites the stored words

Where: `WalletModel.kt:92-96` (setup and wallet are read in one `runCatching`; on failure there is no setup, no wallet
and no lock), `:222-224` and `:244-253` (a new wallet is stored over whatever is there), `Store.kt:168`
(`WordVault.exists()` is never called), `Store.kt:52-53` (files are written without a sync before the rename),
`strings.xml:93` ("The stored setup cannot be read ... Import the setup file again" for every such failure).

What happens: if `setup.bin` or `wallet.bin` cannot be read once (a file cut short by a power loss, a Keystore error, a
stricter check in a later version), the app shows the first screen and, after the setup is imported again as it
advises, "No wallet in this phone yet / Make a new wallet". Accepting writes new words over `words.bin`, although the
old words were still there and readable. With the paper at hand this costs nothing; without it the coins are gone.

Established: confirmed by test `AuditModelTest.aFileThatCannotBeReadOnceDoesNotCostTheStoredWords`: "after one
unreadable file the app shows: problem=STORE (IllegalArgumentException), setup=null, wallet=false, locked=false; a new
wallet is offered: true; the stored words are still the old ones: false".

Fix: read the two files independently; if `words.bin` exists, never store other words without an explicit, separate
confirmation (or offer "rebuild the wallet from the stored words" after the fingerprint); sync files before renaming.

### F-06 (low) Words typed into "Bring back a wallet" are written into the activity's saved state

Where: `Tabs.kt:374` (`var restoring by rememberSaveable`). The paper field at `:477` uses `remember`, which is right.

What happens: whenever the app goes to the background while the field holds text, Android stores the text in the
saved instance state, outside the app's process, where the app can no longer erase it; it also survives the app's
process. It is not written to backups and other apps cannot read it, hence low.

Established: confirmed by test `AuditScreenTest.wordsTypedForBringingAWalletBackStayOutOfTheSavedState`: "words typed
for restore: in the saved state=true, back after a rebuild=true; words typed for a paper address: in the saved
state=false, back after a rebuild=false".

Fix: `remember` instead of `rememberSaveable` in that one line.

### F-12 (low) A paper address with more than 1000 coins cannot be emptied

Where: `core/.../Planner.kt:70`, `:111`, `:124`; `Engine.kt:202-207` (emptying always takes every coin in one transaction).

What happens: a paper address that collected more than 1000 block rewards makes both ways of emptying it fail with
"Too many small coins for one transaction. Send a smaller amount first", which that screen cannot do. By then the words
have been typed into the phone. Nothing is lost, but the user needs another tool.

Established: confirmed by test `AuditPlainTest.aPaperAddressWithManyRewardsCanStillBeEmptied` (1001 coins: both plans
end in TOO_MANY_INPUTS).

Fix: empty in several transactions of at most 1000 coins each, and say so on the screen.

### F-09 (low) What the node says is not checked against what can exist; an absurd answer stops the overview

Where: `node/.../NodeClient.kt:165` (`sats`), `WalletCalls.kt:60`, `:92`; `Engine.kt:102` (only "below zero" is
checked), `:65-69` (plain sums); `Format.kt:10`; `NodeClient.kt:90` (the whole answer is read into memory, no limit);
`NodeClient.kt:107` with `Parts.kt` (the node's error text goes to the screen at any length).

What happens: two coins of 92 233 720 368 NORTH each are accepted; their sum overflows to -2 base units and
`Format.coins` throws while the overview is drawn, so the app stops at every refresh for as long as the node keeps
answering so. Transaction ids (form, upper or lower case), output numbers and duplicates are not checked either, so
the same coin can be counted twice. None of this can move coins: every signature covers the true amounts and the
wallet's own scripts, so a transaction built on false figures is refused by the network (ReviewTest confirms it).
It is a way for a broken or hostile node to stop the app or to show nonsense.

Established: confirmed by test `AuditPlainTest.theNodeCannotMakeTheTotalsOverflow` ("total = -2 base units; on the
screen: Format.coins throws IllegalArgumentException"); the size limit and the error text by reading.

Fix: at the boundary accept amounts only between 0 and the money supply, add with `Math.addExact`, require 64 lower-case
hex characters for ids, output numbers below 2^32, one entry per coin; limit the answer size and the length of any text
of the node that is shown.

### F-10 (low) The id of a payment is whatever text the node returns

Where: `Engine.kt:188` (`return node.broadcast(signed.hex)`), `NodeClient.kt:151`, `WalletModel.kt:332-336`.

What happens: the id shown under "Sent", offered for copying and used to clear the waiting payment is the node's
answer, not `signed.txid`. A wrong answer leaves a waiting payment that never clears and gives the user a false id.

Established: confirmed by test `AuditPlainTest.theIdOfAPaymentIsTheIdOfWhatWasSigned`.

Fix: return `signed.txid` and refuse an answer that differs.

### F-11 (low) A price text can make the overview fail at every start

Where: `WalletModel.kt:286` (the price is stored as typed), `Format.kt:60-65`, `Tabs.kt:93` (called while the overview is drawn).

What happens: a pasted price such as `1e999999999` is stored; `Format.value` then throws an ArithmeticException each
time the overview is drawn with a balance, which is the first screen after unlocking. The way out (reach the More tab
before the node answers, or go offline) is not obvious; clearing the app's data destroys the stored words.

Established: confirmed by test `AuditPlainTest.aPriceTextCannotBreakTheOverview` (three such texts throw).

Fix: accept only plain decimals of sensible length when saving, and make `Format.value` return null on any exception.

### Notes (informational, by reading; no test)

- N-1 Removing the wallet (`Tabs.kt:458`), removing the setup and switching the lock off (`:408`) ask for no
  fingerprint; with F-04 a stranger can destroy the stored words. The remove dialog does not show what the wallet holds.
- N-2 Texts left from the watch-only app are now untrue or misleading: "this app holds no keys" (`strings.xml:84`),
  "Import the setup file again" for every storage error including Keystore errors while making a wallet (`:93`),
  "North Watch" in the setup text, and the README (still describes a watch-only 0.1 with one permission).
- N-3 If the screen lock is removed from the phone, Android destroys the key of the words; the app would then show
  "Something unexpected happened (KeyPermanentlyInvalidatedException)" when sending, with no advice. Only
  `StrongBoxUnavailableException` falls back to the ordinary Keystore (`Store.kt:205`); other StrongBox failures stop
  wallet creation. Both need a phone to confirm.
- N-4 The words of a paper address stay in memory until the address is emptied or dropped (`WalletModel.kt:84`, `:362`);
  locking the app or leaving it does not clear them. Words are held as Java strings and cannot be wiped.
- N-5 History keeps blocks of an abandoned chain after a reorganization deeper than 10 blocks until the app restarts
  (`Engine.kt:128-129`); a transaction then listed twice makes the History list throw (duplicate key, `Tabs.kt:291`).
  The balance is not affected (it comes from `scantxoutset`).
- N-6 No protection against a window drawn over the confirmation (`setHideOverlayWindows`, API 31) and no
  `accessibilityDataSensitive` (API 34) on the screens that show or take words; an accessibility service the user has
  switched on can read them.
- N-7 `WalletModel.change` (`:275`) saves in the background and ignores failures; two quick saves share one temporary
  file name (`Store.kt:51`). Worst case seen by reading: an address or change key is used twice, or F-07 is triggered.
- N-8 Dependency verification pins SHA-256 of 594 components, all "Generated by Gradle" (trust on first download);
  signatures are not verified. Two artifacts were compared with Maven Central (see below).
- N-9 A scan that takes longer than 30 s is reported as "The node cannot be reached".
- N-10 The node runs one `scantxoutset` (and one `scanblocks`) at a time, and the app does not line its own calls up:
  the automatic refresh after a payment (`WalletModel.kt:337`), "Check the payment", "Look at the address" and the
  background job can overlap. In one of my runs a payment prepared right after another ended in the error kind NODE
  for this reason (my reading of the cause; the node's text was not captured). Harmless, but the user sees a node error.

## What I checked and found sound

- Keys, addresses, descriptors, signing: the existing vector and regtest tests re-run (all pass); the signed script is
  always derived from the account, never taken from the node (`AuditCoreTest.aPlanSignsOnlyForTheWalletsOwnScripts`,
  `Spend.kt:62-76`); a lying node cannot make a valid transaction with false amounts (existing ReviewTest, re-run).
- Recipient addresses: 6009 damaged forms of three addresses and 300,000 random texts all refused, nothing but an
  Exception thrown; capitals accepted, mixed case, other network, payment-link text refused (`AuditCoreTest`).
- Typed amounts: 300,000 random texts read exactly as an independent reading of the same rule (`AuditPlainTest`).
- Fees and change: by reading plus the existing property test: fee = rate x exact size, at most 999 + 43 x rate base
  units more when no change is made; rate limited to 1..1000 (the screen offers 1, 2, 5); no output below 1000.
- Double sending by a double tap: `confirmSend` and `paperSend` set their busy flag before anything else (by reading).
- Waiting payments within one run of the app (`AuditModelTest.inOneRunOfTheAppAWaitingPaymentIsLeftAlone`).
- Node address: 30 malformed or tricky addresses refused, only plain https taken, host shown = host called
  (`AuditPlainTest.onlyAPlainHttpsAddressIsTakenForTheNode`); manifest forbids cleartext; no own TLS code at all.
- Redirects 301/302/303/307/308 are not followed and the login is not repeated; error texts carry neither user nor
  password (`AuditNodeTest`, desktop Java). Amounts are read exactly; huge exponents are refused at once (JDK 21).
- Sealed files: AES-256-GCM, fresh nonce from the provider, purpose as associated data, any changed byte refused
  (existing StoreTest; `Store.kt:38-87` by reading). Words: 256 bits from `SecureRandom`, BIP39 checksum, three words
  asked back before anything is stored, only 12 or 24 words taken (`AuditCoreTest`).
- Nothing secret leaves the phone: existing ReviewTest relays every request (re-run); no logging, no analytics, no code
  loaded at run time, no WebView in the sources; no analytics or crash-reporting classes in the APK; the clipboard gets only addresses,
  the public descriptor and transaction ids.
- Background job: service not exported, notification intent explicit and immutable, lock-screen version without the
  amount (`Notify.kt`, by reading).
- Package: hash as given; signed with scheme v2 by "CN=Severka" (EC 256); not debuggable; `allowBackup=false` with
  extraction rules; permissions as in the manifest; exported are only the launcher activity and androidx's
  ProfileInstallReceiver (guarded by DUMP). The four `libsecp256k1-jni.so` are byte-identical to those in the pinned
  AAR; that AAR's SHA-1 and the MD5 of `bitcoin-kmp-jvm-0.32.0.jar` equal what Maven Central publishes (three small checksum files, fetched from the auditor's own environment, not from the MacBook). Every own class
  compiled from these sources is in the APK and the APK has no other own class (names only; D8's lambda classes aside).
  Lint: 11 warnings, none about security.

## What I could not check, and what I recommend for it

Everything below needs a real phone (ideally two: one with StrongBox, one without) and was judged by reading only.

- Android Keystore: that the key of the words is really made with per-use authentication and accepted by
  fingerprint and by screen lock; behaviour when the screen lock is removed or a fingerprint is added (N-3);
  StrongBox fallback. Test: make a wallet, send, remove the screen lock, try to send, bring the wallet back.
- The fingerprint dialog and FLAG_SECURE: screenshots, screen recording and the recent-apps picture in the release
  build, also while a dialog is open.
- F-04 on a phone (steps are given in the finding).
- Keyboards and autofill: that the keyboard in use does not learn the words (the fields ask for the password type but
  show their text), and that no password manager offers to save them. Compose now exposes text fields to autofill
  services; setting `importantForAutofill = noExcludeDescendants` on the activity's window would rule this out.
- TLS on Android: certificate and host-name checks, behaviour with a user-installed CA, redirects and number parsing
  in Android's own HTTP and math code (tested here on desktop Java 21 only).
- The job scheduler, notifications on a locked screen, the camera and QR reading on a device (only `Scan.address`
  was exercised), and the native library on phone processors (the app's self-test is the means for that).
- That the APK was built from exactly these sources: class names match, bytecode was not compared; a reproducible
  build recipe would allow it. The release key and how it is kept were out of scope by instruction.
- The node side: the real `rpcwhitelist`, the TLS endpoint in front of the node, and TrueNorth's relay rules on
  mainnet (replacement of unconfirmed payments was observed on regtest with the official v30.5.4 program).
- Hardware attacks, a rooted phone, malware with accessibility rights.

## How to rerun my tests

```
cd ~/north-build/audit-0.3.1/north-wallet && . ~/north-build/env.sh
gradle --offline --no-daemon -q --continue :core:test :node:test :app:testDebugUnitTest     # everything, about 4 minutes
gradle --offline --no-daemon -q --continue :app:testDebugUnitTest --tests "*Audit*"          # only the audit tests of the app
sh ../tools/summ.sh            # one line per test class and the totals
sh ../tools/fails.sh Audit     # outcome of every audit test, failure messages, and the lines the tests print
```

Gradle ends with "exit 1" because 12 audit tests fail by design: each of them is one finding and should pass once the
finding is fixed. The original 41 tests are untouched and pass. Regtest tests need TN_BIN (set by env.sh).
`sh tools/apk-native.sh` and `sh tools/apk-classes.sh` repeat the comparison of the APK (they write under /tmp).

Files added (all inside `audit-0.3.1/`):

- `north-wallet/core/src/test/kotlin/north/core/AuditCoreTest.kt` (5 tests, all pass)
- `north-wallet/node/src/test/kotlin/north/node/AuditNodeTest.kt` (4 tests, all pass)
- `north-wallet/app/src/test/kotlin/io/github/severka_monitor/northwatch/AuditSupport.kt` (regtest node, relay, fake node, stores)
- `.../AuditPlainTest.kt` (6 tests: F-09, F-10, F-11, F-12 fail; 2 pass)
- `.../AuditModelTest.kt` (5 tests: F-01, F-02, F-03, F-07 fail; 1 passes)
- `.../AuditScreenTest.kt` (4 tests: F-04, F-05, F-06, F-08 fail)
- `tools/` (summ.sh, fails.sh, apk-native.sh, apk-classes.sh, two snippets), `logs/` and `run*.log` (Gradle output of each run),
  `app-build.gradle.kts.orig`.

## Test record

Last full run: 14 November 2026, 01:08 to 01:11 (`logs/run8-full-final.log`), 65 tests, 0 skipped:

- 41 existing tests in 14 classes: all pass (the regtest tests ran against the official programs named by TN_BIN).
- 24 audit tests in 5 classes: 12 pass (what was found sound), 12 fail by design (one per finding F-01 to F-12).
- The 12 failing tests gave the same result in each of four runs. One of my passing tests failed once (run7) for the
  reason described in note N-10 and now waits for the app's refresh before it pays again; nothing else was unstable.
- Lint (`:app:lintDebug`): 11 warnings and hints, none about security.
- During the last runs another regtest node, not started by me (data directory under `~/north-build/emu/run/`), was
  running on the same computer; I did not touch it.
- Left behind outside this folder: only `/tmp/north-audit-apk` (the unpacked APK used by the two apk scripts).

Status: FINAL
