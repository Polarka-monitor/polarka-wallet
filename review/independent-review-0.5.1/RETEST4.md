# Polarka Wallet 0.5.1 (98a2b77) - fifth look, short

Status: see the last line of this file (FINAL only if it says so there).

Scope: `git diff 79cec8f 98a2b77` only, 14 November 12:50-13:10, in my own clone `audit-0.5.1/polarka-wallet`. The
repository itself was not changed. Read in full: the diff of the main sources and of the English and Czech strings,
README.md, docs/guide.md, docs/navod.md, review/changes-0.5.1.md, the new tests; around the diff: `keepWords`, `lock`,
the paper functions, `AndroidKeystoreKey`, `PhoneOwner`, the lifecycle calls in MainActivity. Not read: the emulator
scripts. Signing: `app/build.gradle.kts:24-28` unchanged; no task with "Release" in its name was run.

## Verdict

1. The change does what it says. `checkBackup` is sound, `Words.fault` is null exactly when `Words.valid` is true, and
   neither typed words nor a message that names one reach a log, a notification, the saved state of the screen, the
   wallet's file, a crash report or the node.
2. One low finding that this change opened, which I would close before the package is handed on: R4-01 (two taps on
   "Store the wallet"). It is two small changes.
3. One sentence of the README that the code does not keep for the words of a paper address: R4-02. Three notes.
4. Nothing here changes the verdicts of the third and fourth look.

## Findings (most severe first)

### R4-01 (low) "Store the wallet" can now be tapped twice, and two stores can leave words that cannot be opened

Where: `Tabs.kt:488` (the field is no longer emptied at the tap, so the button stays enabled, and nothing on the
screen moves until the phone's dialog comes), `WalletModel.kt:512-536` (`keepWords` has no guard against a second
call; whether words are stored already is asked before the owner is, not when they are written),
`Store.kt:281-306` (`AndroidKeystoreKey.key`: looks for the key, then makes one; a key made under a name that is
taken replaces the one that was there).

What happens: between the tap and the dialog the phone makes the key for the words, which takes a security chip
a moment (up to a second or two). A second tap in that moment starts a second store. Both find no stored words and
no key, both make a key, and the second key takes the place of the first. The first dialog comes; if the owner
answers it before the second one arrives, the words are sealed with the first key and the wallet is shown as ready.
Then the second dialog comes. If the owner answers it too, the words are written again with the second key and all
is well. If the owner sends it away (the wallet is ready, why a second dialog), the phone holds the second key and
words sealed with the first: the wallet receives, shows no problem, and cannot pay. The way out is the paper:
remove the wallet and bring it back. Before 0.5.1 this button could not be tapped twice, because the field was
emptied at the first tap (79cec8f, `Tabs.kt:482`). "Save" on the screen of a new wallet (`MainActivity.kt:327`) has
allowed the same two taps since 0.3.1 (checked in 3f9c400); I had not seen it. There the paper is the only other copy.

Established: failing test `Retest4Test.aSecondTapOnStoreTheWalletLeavesWordsThatCanBeOpened` (my clone only), two
taps on the real button: "the button was still there for the second tap: true; the owner was asked 2 time(s), keys
made under the one name: 2, wallet stored: true, shown as its problem: UNLOCK; the stored words can be opened: false
(AEADBadTagException)". In the test a stand-in takes the place of the phone's Keystore and does what
`AndroidKeystoreKey.key` does with it (look, make slowly, replace), and the owner answers the first dialog and
refuses the second. Not tried on a phone, and an emulator will not show it: what decides is how long the phone
takes to make a key. A separate security chip, which the code asks for first (`Store.kt:302`), makes one key after
the other and can need from tenths of a second to more than a second for each; then the first dialog can be
answered before the second key exists, as in the test. Where keys are made in a few hundredths of a second, the
second dialog takes the place of the first before a finger is on it, and the dialog that is answered seals with the
key that stays: no harm there. I have not measured any phone; a quick double tap on "Store the wallet" on a real one
shows whether a second dialog comes.

Why low and not more: it takes a second tap and a dialog sent away, and the coins stay reachable with the paper.
Why still before the package goes out: the wallet shows nothing until the first payment fails.

Fix: let `keepWords` do nothing while a store is under way, and show that on the screen (the button off, "busy");
and make `AndroidKeystoreKey.key` one step (`@Synchronized`), so that a second caller finds the first key. Either
one closes it; the first also spares the owner the second dialog.

### R4-02 (low, text against code) The words of a paper address do not go when the page is left

Where: README.md:71-74 (new: typed words "live only in the field they are typed into" and go "when the page is
left"), `WalletModel.kt:827` (after a look that worked, the words of a paper address are kept by the model),
`WalletModel.kt:931-937` (`typedWordsGone` takes messages away, not the look).

What happens: nothing new. As in 0.5.0, a paper address that was looked at stays open, with its words in memory,
until it is emptied, the look is dropped, or the app has been away for a minute. The owner can go to another page
and come back and empty it. The new sentence says otherwise.

Established: failing test `Retest4Test.theWordsOfAPaperAddressGoWhenThePageIsLeft`: "a paper address was looked at,
then the page was left: the look is still held: true; back on the page: the field for its words is shown: false,
the address can be emptied: true".

Fix: say it in the README as the model's own comment does (`WalletModel.kt:809`). Dropping the look when the page is
left would be stricter, but it must wait for a payment that is under way, as `lock()` does.

### Notes

- N4-1 A message that names what was typed stays when the text is deleted or changed by hand in the fields for
  bringing a wallet back and for a paper address (`Tabs.kt:484`, `:613`); the field of the paper check takes it away
  (`backupEdited`). And what it names is not always "not a recovery word": two words typed without the space, or a
  word with a comma or a number, are named as typed, up to 16 characters, so real words of the paper can stand in the
  message. Test `aMessageThatNamesWhatWasTypedGoesWhenTheTextIsDeletedByHand` (fails by design): "the message names
  them as one (real words of the paper on the screen): true; after the field was emptied by hand the message is
  still there: true". Fix: take the message away when the field is edited, as `backupEdited` does.
- N4-2 "Goes when the app has been in the background for a minute" (README, guide, navod) happens when the app
  comes back after more than a minute (`MainActivity.kt:76-84`, `WalletModel.returned`), not by a clock. Nothing is
  on the screen meanwhile, but until then the text is still in the app's memory.
- N4-3 `problem_words_fit` and the two guides say that one word "is a different word than on your paper". The app
  cannot know the paper. When the paper itself was copied wrongly with a word that is on the list (the case that
  led to 0.5.1), the owner types what the paper says and is told that it differs from the paper. Better: "one of
  them is not the right word, or the order is different; if what you typed is what your paper says, the paper was
  copied wrongly". With a paper of 12 words, one wrong word in sixteen passes the check of the words and is answered
  with `backup_other` ("of another wallet") or, when bringing back, with an empty wallet; `backup_other` could say
  that one wrong word can look like this.

## Your questions

- a) Typed words. Longer than the README says: the words of a paper address after a look that worked (R4-02), and a
  message after the text was deleted by hand (N4-1); "a minute in the background" means on return (N4-2). Otherwise
  as stated: the three fields are `remember`, never saved (`Tabs.kt:462`, `:597`, `:680`); `lock()` empties them and
  takes the messages away also where the wallet does not lock (`WalletModel.kt:192-208`); leaving the page does
  (`Tabs.kt:463`, `WalletModel.kt:931-937`); a wallet that is stored does (`Tabs.kt:465`). `Problem.detail`: the main
  sources have no log line and no print; a notification holds a received amount only; the model's state is never
  written to the saved state; the wallet's file has no such field; there is no crash reporter, and no exception
  carries typed text (`Problem.of` passes on class names only); nothing of it is sent to a node. `Problem` is a data
  class, so whatever printed the state one day would print the typed word; today only a failing test does.
- b) `checkBackup` (`WalletModel.kt:904-920`). It cannot say "They match" for other words: it compares the account
  key derived from the typed words with the wallet's, after the same splitting that bringing a wallet back uses. It
  weakens nothing: the stored words are not opened, and whoever has the open app can already copy that account key
  from the More page (the descriptor) and compare at home. Small: a result that arrives after the page was left is
  shown on return (it holds no words), and the field can be edited while the check runs.
- c) `Words.fault` (`Planner.kt:40-48`) is null exactly when `Words.valid` is true: by reading, and by my test with
  5605 cases (capitals, swapped, joined, one more, one fewer, other valid lengths, empty): no disagreement. Places
  count from one and are those of what was typed; at most six are named, each cut to 16 characters; a word with a
  colon or a percent sign does no harm (`Parts.kt:225-230`).
- d) Texts. Not kept: README.md:71-74 for a paper address (R4-02). Not exact: "a minute in the background" (N4-2),
  "than on your paper" (N4-3, English and Czech, both guides). "Nothing changed in ... how the words are stored"
  (changes-0.5.1.md) is true of the code that stores, not of how often it can be started (R4-01). Kept: the other
  new statements in README, guide, navod and changes-0.5.1.md, including "one case out of eight" and "learns no
  more from the check than from that address". The Czech strings say what the English ones say. "122 tests, none
  skipped": the count fits (116 and six new ones); I ran the new classes only.
- e) The five new tests do what their names say. I took the change back piece by piece in my clone (six changes, two
  runs) and each was noticed by the test that names it: the field emptied at the tap, a lock that leaves typed
  words, a page left that leaves the message, a check that says "match" for any valid words, the old notice for a new
  wallet, the public node first. They do not cover: two taps (R4-01), a paper look that worked (R4-02), text deleted
  by hand (N4-1). "Away for a minute" is played by calling `model.lock()`; that `returned()` calls it also for a
  wallet that does not lock is by reading.
- f) Nothing else. The first screen: no address filled in, "Connect" off until one is typed, the own node first.

## What I ran, and what I did not check

- run1: at 98a2b77, WordsHelpTest 4, FirstScreenTest 1, AuditScreenTest 4, core WalletEngineTest 9: all pass.
- run2: my tests for the first time, with the outcome of run5.
- run3, run4: the author's two classes with my six changes to the main sources (tools/mutate.py, sets A and B): the
  tests named above fail, the others pass. The sources were put back with git after each run; `git status` shows
  only my two test files, HEAD 98a2b77.
- run5 (last, clean sources): `Retest4Test` 3 tests, fail by design (R4-01, R4-02, N4-1) with the lines quoted above;
  core `Retest4CoreTest` 2 pass; WordsHelpTest 4 and FirstScreenTest 1 pass.
- Rerun: `cd ~/north-build/audit-0.5.1/polarka-wallet && . ~/north-build/env.sh && gradle --offline --no-daemon -q
  --continue :core:test --tests "north.core.Retest4CoreTest" :app:testDebugUnitTest --tests "*Retest4Test*";
  sh ../tools/fails.sh Retest4`
- Not checked: anything on a phone or emulator, the signed package, the whole suite and lint at 98a2b77 (the machine
  was busy, load 13), the emulator scripts, what Android's Keystore and its dialog really do in R4-01.
- Left: my clone with two untracked files (`app/src/test/kotlin/org/polarka/wallet/Retest4Test.kt`,
  `core/src/test/kotlin/north/core/Retest4CoreTest.kt`), `tools/`, `logs/`. Nothing outside `audit-0.5.1/`.

Status: FINAL
