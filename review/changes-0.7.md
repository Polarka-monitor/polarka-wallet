# What 0.7 changed

## 0.7.0 (20 November 2026, commit 6dc0f38)

0.7.0 was not published. It went to one phone, its owner's, as the personal edition.

- **Two editions from one source** (`productFlavors` "personal" and "public" in `app/build.gradle.kts`). The
  personal one shows the wallet's first address always and has a faucet on the receive page; the public one
  shows a new address for each payment unless its owner asks for a fixed one, and has no faucet.
- **A fixed receiving address.** `WalletInfo.fixedAddress` in the wallet file, written only when the owner
  switches it (More, Wallet in this phone); without it the edition decides. Only what the receive page shows
  changes: the keys the wallet looks at, change, the choice of coins, waiting and dropped payments and the
  wallet file are as before, and a file of 0.6.1 opens in 0.7 and the other way round.
- **Polarka's own wallet server** at `https://node.polarka.org` is offered on the first screen beside the
  Northbound project's, which stays filled in.
- **The faucet** (personal edition): the phone does the work of faucet.py's challenge and asks
  `faucet.polarka.org` for a first coin to the address shown.
- A link to polarka.org under More.

## The eighth look (23 November 2026)

An independent reviewer, an AI model that had not seen the code being written, looked at the two commits
after v0.6.1 (`independent-review-0.7.0/`). It found nothing that weakens the safeguards of 0.6.1 and nothing
through which a server, the faucet or the network can make the wallet pay twice or show coins that are not
there. It would not have held a release for a safety reason; it would have held it for the texts (L2, L3,
L4). Findings: 0 critical, 0 high, 0 medium, 5 low, 5 notes.

## 0.7.1: what was done with each finding

| Finding | What was done | Where | Tests |
|---|---|---|---|
| L1 (low). The faucet's questions had no time limit as a whole, its work could not be stopped, leaving the page orphaned it, and a second one could start beside it. | Each question has a deadline of 60 seconds as a whole: a watch closes the connection, and the reader gives up between two reads (as `ServerClient` does). The work ends at the challenge's time and never later than ten minutes from its start (faucet.py's `CHALLENGE_LIFE`), whatever the faucet says. The work looks every 65,536 tries whether it was stopped. The question is held by a `FaucetModel` of the activity, not by the page, so it is neither doubled nor orphaned; it runs on the threads for waiting (`Dispatchers.IO`); the screen shows that it is working and a Stop button. A challenge that has run out by the phone's clock is not worked on. | `app/src/personal/.../Faucet.kt`, `FaucetPart.kt` | `FaucetTest.anAnswerThatTricklesInIsNotWaitedForWithoutEnd`, `.theOwnerCanStopItWhileItWaitsAndWhileItWorks`, `.theWorkLastsNoLongerThanAChallengeOfFaucetPyLives`, `PersonalEditionTest.theFaucetCanBeStoppedFromTheScreen`, `.theFirstAddressAlwaysAndTheFaucetForIt` (leaving the page and coming back) |
| L2 (low). The first screen called every typed server Northbound's, Polarka's own included. | `Setup.ownerOf` says whose a server is from its address alone: Northbound's or Polarka's for exactly the two addresses offered, however they are typed (with or without `https://`, a slash at the end, capitals), and for anything else that the app does not know who runs it. Three texts follow from it, and the chips light up by it. The check before saving names who runs the server, and so does More. | `Setup.kt`, `MainActivity.kt`, `Tabs.kt`, `Parts.kt`, `strings.xml` | `Version07Test.whoseAServerIsFollowsFromItsAddressAlone`, `ServerOwnerScreenTest` (both editions) |
| L3 (low). What a fixed address gives away was said in part, where it is switched on was named wrongly ("Settings"), and switching it off could show the fixed address as a new one. | The receive page and the switch say what is given away: every payer sees what the others paid and the total, and once its coins are spent, the payments made with them and the wallet's other addresses spent beside them. The way back is named as it is: More, Wallet in this phone. Switching the fixed address off before anything arrived at it moves the next address past it. | `strings.xml` (both languages), `Tabs.kt`, `WalletModel.saveFixedAddress` | `PersonalEditionTest.switchedOffBeforeAnythingCameTheFixedAddressIsNotOfferedAsANewOne`, `PublicEditionTest.aNewAddressForEachPaymentUnlessTheOwnerAsksForAFixedOne` |
| L4 (low). The README still described 0.6.1. | README brought to 0.7.1: the state, where the package is, the two editions, Polarka's server, the fixed address and what it costs, the build commands. The guides (`docs/guide.md`, `docs/navod.md`) too. | `README.md`, `docs/` | by reading |
| L5 (low). Nothing in the suite tested what the editions do; two test names claimed more than they tested. | One screen test for each edition. Public: a new address after a payment and after "Next address", the first one only while the owner asks for it, no faucet on the screen and none of its classes or texts in the edition. Personal: the first address whatever was used before, no "Next address", the faucet asked of a stand-in. The faucet's tests now hold answers that faucet.py itself worked out for challenges of its form, and they ask a stand-in that checks the work with a counter of its own and answers as faucet.py does, and one that does not keep to the rules. | `app/src/testPublic`, `app/src/testPersonal` | `PublicEditionTest`, `PersonalEditionTest`, `FaucetTest`, `FaucetStandIn` |
| N1 (note). The two editions are one app to Android, so a mix-up would be silent. | Kept so on purpose: the owner's phone keeps its wallet. `review/static-checks.sh` now takes the edition and writes `06-edition.txt`: the flavour and the fixed-address default read from the package's code, and whether the faucet's class, address or texts are in it. A package is published only when that file says "public edition". | `review/static-checks.sh`, `app/build.gradle.kts` (comment) | run before each release |
| N2 (note). The public package carried the faucet's code, address and texts, unreachable. | The faucet's code and texts moved to `app/src/personal`. The public edition has an empty `FaucetPart` in `app/src/public`; its package holds no faucet at all. `BuildConfig.FAUCET` is gone. | `app/src/personal`, `app/src/public`, `Tabs.kt` | `PublicEditionTest.thisIsThePublicEditionAndItHoldsNoFaucet`, `06-edition.txt` |
| N3 (note). The faucet's texts said more than the faucet's answer. | "The faucet says it sent it"; the wait for a block is "usually within a few minutes"; NOT_NOW has a text of its own (the payment may still be on its way: do not ask again today); what the phone found by itself (no answer in time, unreachable, unreadable, more work than the phone does, a challenge that ran out, no answer found) is said as the phone's finding and not as the faucet's word. | `app/src/personal/res`, `FaucetPart.kt` | `FaucetTest.ofWhatAFaucetSendsOnlyItsWordsFromTheListAreShown` |
| N4 (note). Small things in the diff. | The comment about the lock stands above `saveLock` again. Emptying a paper address into the wallet goes to the same address that the receive page shows (`WalletInfo.shownAddress`). The commit message of 0.7.0 stays as it was; this file says what it got wrong ("Settings", "the first address always"). | `WalletModel.kt`, `Store.kt`, `Tabs.kt` | `Version07Test.theAddressShownIsTheFirstWhileFixedAndTheNextOneOtherwise` |
| N5 (note). Where walletd, the server behind `node.polarka.org`, differs from the real server. | Not in this repository: walletd is part of polarka-agent. Noted there for its next version: a revision that does not start again at 1 after a restart, readiness on regtest without the initial-download flag, and a limit on how often it is asked. Until then the README says that `node.polarka.org` is easy to make slow or unreachable. | `README.md` (Limits) | none here |

The version is 0.7.1 (code 14), so that it installs over the 0.7.0 personal edition in its owner's phone.

## What was not done

- No reviewer has looked at 0.7.1. The changes above were checked by their author, with the tests named.
- 0.7.1 has not been run on an emulator or on a phone.
- The reviewer could not rebuild the packages of 0.7.0 from their sources; neither has anybody else.

## The ninth look (23 November 2026)

An independent security review of 0.7.1 (commit 9eb3df1) by people outside this project, whose names are not
given here. It read the app, `core/`, `node/` and the server software (`walletd.py`, `faucet.py`), and the
reports of the eight looks before it; it did not run the app. It found no critical and no high fault, nothing
through which a server, a node or another app on a phone that is not rooted could take the recovery words,
put another address in place of the one typed, or take coins. Findings: 0 critical, 0 high, 4 medium, 5 low,
6 notes. It asked for the medium ones and the first three low ones to be put right before a public release,
and for 0.7.1 to be tried on an emulator and on a real phone.

## 0.7.2: what was done with each finding

| Finding | What was done | Where | Tests |
|---|---|---|---|
| M-1 (medium). Sums that were not checked could end the app: the total of the wallet and the watched addresses, and the rewards on the mining page. | Every sum the screens show is worked out once, when the look is made, with the check that no amount and no sum is larger than all coins that can exist (`NodeClient.total`); the look then fails with the node's error, shown as a problem, and nothing reaches a screen. The rewards are one of those sums. The same for a paper address. The expected coins on the mining page stay within that bound. | `Engine.kt` (`Snapshot`, `PaperFound`), `Tabs.kt` | `NinthLookTest.sumsThatCannotExistStopTheLook` |
| M-2 (medium). A payment being signed and the removal of the wallet could meet: the removed wallet came back to life in its file. A paper address worked out with one node or server stayed when the setup changed. | While a payment is signed and sent, a paper address is emptied, or words are stored, the wallet is not removed and the setup is not changed or forgotten; the owner is told to wait. Asked again after the owner's yes. A payment whose wallet, node or server changed while it was signed is neither written down nor sent. Removing the wallet, a new setup and forgetting the setup drop the paper address, its words, and a payment waiting for the owner's yes. | `WalletModel.kt` | `NinthLookTest.theWalletIsNotRemovedWhileAPaymentIsSigned` (against a regtest node), `.whileTheWordsAreUnlockedNothingElseChangesTheWallet` |
| M-3 (medium). A typed amount above all coins that can exist made a plan whose sums wrapped around 64 bits; a wrong transaction was signed and the wallet file could no longer be read. | A typed amount is at most 2 000 000 000 NORTH. The planner refuses coins and amounts that cannot exist before it subtracts anything, and signing checks every input and output and their sums. | `Format.kt`, `Planner.kt`, `Spend.kt` | `NinthLookCoreTest` (5 tests), `NinthLookTest.anAmountAboveAllCoinsIsNoAmount`, `FormatTest`, `AuditPlainTest` |
| M-4 (medium). The node's client took an HTTP failure without the node's error as an empty answer ("not held", "spent"), took any block time, and had no time limit for a question as a whole. | A failure status without the node's own error is an error, and so is an answer without a result. `getmempoolentry` and `gettxout` take only the shapes the node answers with. A block time is between 1970 and the year 3000, as the server's. Each question has a time as a whole (the time to connect and one read's time more), kept by a watch as in `ServerClient`, and its connection is closed in every case. | `NodeClient.kt`, `WalletCalls.kt` | `NinthLookNodeTest` (3 tests) |
| L-1 (low). Autofill was not switched off; new words stayed on the screen without end while the app was away; the descriptor went to the clipboard without the mark of a sensitive text; the bytes of the words were not overwritten. | The app is excluded from autofill as a whole. New words that wait to be written down are dropped after ten minutes away, with a note to throw a paper with them away. The descriptor is copied as sensitive. The bytes of the words are overwritten once they are sealed or read (the strings made of them cannot be). | `MainActivity.kt`, `WalletModel.kt`, `Parts.kt`, `Tabs.kt`, `Store.kt` | `NinthLookTest.newWordsDoNotWaitForEver`; the rest by reading |
| L-2 (low, personal edition). The faucet's last word stayed beside another address or a new wallet; its watch could close a connection that was already read; a failure status was not refused. | What the faucet said is kept with the address it was about and shown only there. The watch and the reader are kept apart as in `ServerClient`. A failure status without one of the faucet's words is no answer. | `app/src/personal/.../Faucet.kt`, `FaucetPart.kt` | `FaucetTest.ofWhatAFaucetSendsOnlyItsWordsFromTheListAreShown` |
| L-3 (low). A setup for another network than the wallet's was taken; one payment of many coins used up what the node is asked about the others; a second tap asked the owner twice. | A setup for another network than the wallet's is refused, when it is offered and when it is saved. The node is asked about one coin of every waiting payment before a second coin of any; the number of questions stays as it was. Building the wallet again from stored words runs once at a time. | `WalletModel.kt`, `Setup.kt`, `Engine.kt`, `Tabs.kt` | `NinthLookTest.aSetupForAnotherNetworkIsNotTaken`, `.whileTheWordsAreUnlockedNothingElseChangesTheWallet` |
| L-4, L-5 (low). The server software (`walletd.py`, `faucet.py`). | Not in this repository: put right in polarka-agent (a time for a whole request to arrive, a revision that starts at a number drawn at random, an address named back as it was asked, ids in a history cursor read in either case; with `--trust-proxy` the faucet listens on loopback only, and its asker keeps to its own bounds). | polarka-agent | its tests |
| N-1 to N-6 (notes). | Not changed in 0.7.2, apart from what the fixes above touch. | | |

The version is 0.7.2 (code 15).

## What was not done in 0.7.2

- Nobody independent has looked at the fixes. They were checked by their author, with the tests named.
