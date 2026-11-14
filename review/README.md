# Review of Polarka Wallet

This folder holds what is needed to repeat the checks made on a release, the results of the author's own review
of 0.3.1 (`evidence-0.3.1/`, made when the app was still called North Wallet), the report of the independent
review of the same version (`independent-review-0.3.1/`), the report of the same reviewer's second look at 0.4.0
(`independent-review-0.4.0/`), and the scripts that run the package on emulators (`emulator/`). None of it
replaces a review by an independent human expert.

## How to repeat the checks

Run everything from the project folder with JDK 21, Gradle 8.14.3 and the Android SDK (platform 36,
build tools 36.0.0) on the path. `TN_BIN` must point to a folder with the official `truenorthd` and
`truenorth-cli`; the tests start their own regtest node from it and never touch a real network.

| Check | Command | Passes when |
|---|---|---|
| All tests (93) | `gradle :core:test :node:test :app:testDebugUnitTest`, then `sh review/tests-summary.sh` | exit 0; 93 tests, 0 failures, 0 skipped |
| The findings of the independent review and of its retest stay fixed | the `Audit*Test` classes in the three modules, `RetestModelTest`, `AfterRetestTest`, `AfterRetestScreenTest` | all pass |
| The package on emulators (Android 12, 14, 16) | `review/emulator/README.md` | each script ends with `ALL 11 STEPS PASSED` or `LOCK STEPS PASSED` |
| A public node never gets the account key | app test `PublicNodeTest` (needs python3 and `gateway.py` of polarka-node) | prints `PUBLIC NODE OK: ...` |
| Nothing secret leaves the phone | app test `ReviewTest.nothingSecretLeavesThePhone` | prints `REVIEW OK: ... no word, seed, extended private key or private key in any of them` |
| A lying node cannot make the wallet lose coins | app test `ReviewTest.aLyingNodeCannotMakeTheWalletLoseCoins` | prints `REVIEW OK: foreign coin refused, ...` |
| Coin selection and fee | core test `PlannerPropertyTest` (20,000 random wallets) | prints `PLANNER OK` |
| Words to addresses, against two other implementations | see below | `CROSSCHECK OK: 300 phrases, 0 different` |
| Lint | `gradle :app:lintDebug` | 0 errors |
| Sources and release package | `gradle :app:assembleRelease`, then `sh review/static-checks.sh review/evidence-X.Y.Z` | read the files it writes |
| Libraries unchanged | every build reads `gradle/verification-metadata.xml` | the build stops if a library differs from its recorded SHA-256 |

### Words to addresses

    NORTH_CROSSCHECK=/tmp/phrases.tsv gradle :core:test --tests '*PlannerPropertyTest*' --rerun-tasks
    python3 review/crosscheck.py /tmp/phrases.tsv PATH_TO_COLD_ADDRESS_TOOL "$TN_BIN"

The first command lets the wallet's own code turn 300 random phrases into a descriptor and three addresses each.
The second repeats the work with code that shares nothing with the wallet: the cold address tool
(Python, standard library only) turns the words into the descriptor, and TrueNorth Core derives the addresses
from the descriptor. The phrases are random and used once; delete the file afterwards.

## What these checks do not show

- How the phone itself behaves: the keystore, the fingerprint dialog, the camera and the background check
  run only on a real device and were tried by hand.
- That the libraries are free of faults. The recorded checksums only show that they did not change after
  the day they were first downloaded.
- That somebody else building the same sources gets the same package.

## Changing a library

After a deliberate version change run the build once with `--write-verification-metadata sha256` and look at
what changed in `gradle/verification-metadata.xml` before keeping it.
