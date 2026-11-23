# Review of Polarka Wallet

This folder holds what is needed to repeat the checks made on a release, the results of the author's own review
of 0.3.1 (`evidence-0.3.1/`, made when the app was still called North Wallet), the report of the independent
review of the same version (`independent-review-0.3.1/`), the report of the same reviewer's second look at 0.4.0
(`independent-review-0.4.0/`), of its third and fourth look at 0.4.1, 0.4.2 and what followed
(`independent-review-0.4.2/`), of its fifth look at what 0.5.1 changed (`independent-review-0.5.1/`, with
`changes-0.5.1.md`) and of its sixth look at 0.6.0 (`independent-review-0.6.0/`, with `changes-0.6.0.md`), a
stand-in for a wallet server that the tests use (`standin/`), and the scripts that run the package on
emulators (`emulator/`). `changes-0.6.1.md` says what 0.6.1 changed when a wallet server changed its answers,
and `independent-review-0.6.1/` holds the report of the seventh look, at those changes, by a reviewer of the
same kind that began afresh. `independent-review-0.7.0/` holds the eighth look, at 0.7.0 (two editions, a
fixed address, the faucet, Polarka's server), and `changes-0.7.md` says what 0.7.0 changed and what 0.7.1
did with each of its findings. None of it replaces a review by an independent human expert.

## How to repeat the checks

Run everything from the project folder with JDK 21, Gradle 8.14.3 and the Android SDK (platform 36,
build tools 36.0.0) on the path. `TN_BIN` must point to a folder with the official `truenorthd` and
`truenorth-cli`; the tests start their own regtest node from it and never touch a real network. The tests
about a wallet server also need python3 (3.9 or later), for the stand-in.

| Check | Command | Passes when |
|---|---|---|
| All tests, both editions | `gradle :core:test :node:test :app:testPublicDebugUnitTest :app:testPersonalDebugUnitTest`, then `sh review/tests-summary.sh` | exit 0; 210 tests with the public edition and 218 with the personal one (core 34, node 31, app 145 and 153), 0 failures, 0 skipped |
| What each edition does | `PublicEditionTest` (public), `PersonalEditionTest` and `FaucetTest` (personal), `ServerOwnerScreenTest` and `Version07Test` (both) | all pass |
| The findings of the independent review, of its retest and of its later looks stay fixed | the `Audit*Test` classes in the three modules, `RetestModelTest`, `AfterRetestTest`, `AfterRetestScreenTest`, `Retest2ModelTest`, `Retest3ModelTest`, `ThirdLookTest`, `Retest4Test`, `Retest4CoreTest`, `SixthLookTest`, `SixthLookScreenTest`, `AfterSixthLookPlainTest`, `AfterSeventhLookTextsTest` | all pass |
| A wallet server, or a public node, cannot make the wallet pay the same thing twice, take a coin for the wallet's that is not, or hold a look or a payment without end | app tests `SixthLookTest`, `ServerPayTest`, `ServerEngineTest`, `ServerModelTest`, `StandInTest`; node test `ServerClientTest` | all pass |
| A payment that did not go through cannot arrive beside the next one | `NotGoneThroughTest`, `NotGoneThroughPlainTest`, `NotGoneThroughScreenTest`, core test `PlannerMustTest`, node test `SpendersTest` | all pass |
| The package on emulators (Android 12, 14, 16) | `review/emulator/README.md` | each script ends with its line of success (`ALL 12 STEPS PASSED` with a node, `ALL 18 STEPS PASSED` with a wallet server, `LOCK STEPS PASSED`, `NOT GONE THROUGH: ALL 6 STEPS PASSED`, `UPGRADE: ALL 5 STEPS PASSED`, `A SERVER THAT SAYS NO: ALL 6 STEPS PASSED`, `A SERVER THAT CHANGED ITS ANSWERS: ALL 6 STEPS PASSED`) |
| The stand-in answers like the real wallet server | `review/standin/README.md` (it reads the public server, so it is not part of the suite and is not to be repeated often) | no difference is printed |
| A public node never gets the account key | app test `PublicNodeTest` (needs python3 and `gateway.py` of polarka-node) | prints `PUBLIC NODE OK: ...` |
| Nothing secret leaves the phone | app test `ReviewTest.nothingSecretLeavesThePhone` | prints `REVIEW OK: ... no word, seed, extended private key or private key in any of them` |
| A lying node cannot make the wallet lose coins | app test `ReviewTest.aLyingNodeCannotMakeTheWalletLoseCoins` | prints `REVIEW OK: foreign coin refused, ...` |
| Coin selection and fee | core test `PlannerPropertyTest` (20,000 random wallets) | prints `PLANNER OK` |
| Words to addresses, against two other implementations | see below | `CROSSCHECK OK: 300 phrases, 0 different` |
| Lint | `gradle :app:lintPublicDebug :app:lintPersonalDebug` | 0 errors |
| Sources and release package | `gradle :app:assemblePublicRelease`, then `sh review/static-checks.sh review/evidence-X.Y.Z public` | read the files it writes; `06-edition.txt` must end with "public edition" before the package is published |
| Libraries unchanged | every build reads `gradle/verification-metadata.xml` | the build stops if a library differs from its recorded SHA-256 |

### Words to addresses

    NORTH_CROSSCHECK=/tmp/phrases.tsv gradle :core:test --tests '*PlannerPropertyTest*' --rerun-tasks
    python3 review/crosscheck.py /tmp/phrases.tsv PATH_TO_COLD_ADDRESS_TOOL "$TN_BIN"

The first command lets the wallet's own code turn 300 random phrases into a descriptor and three addresses each.
The second repeats the work with code that shares nothing with the wallet: the cold address tool
(Python, standard library only) turns the words into the descriptor, and TrueNorth Core derives the addresses
from the descriptor. The phrases are random and used once; delete the file afterwards.

## What these checks do not show

- A real wallet server. The tests run against a stand-in that was written from the server's sources and
  compared with the public server by reading. From the real server the client has read the coins and the
  history of public addresses, and an emulator the height of the chain and one watched address. Payments
  went through it from this app once: on 16 November 2026, from one real phone with 0.6.1, in a test by
  hand of which no log was kept.
- How the phone itself behaves: the keystore, the fingerprint dialog, the camera and the background check
  run only on a real device and were tried by hand.
- That the libraries are free of faults. The recorded checksums only show that they did not change after
  the day they were first downloaded.
- That somebody else building the same sources gets the same package.

## Changing a library

After a deliberate version change run the build once with `--write-verification-metadata sha256` and look at
what changed in `gradle/verification-metadata.xml` before keeping it.
