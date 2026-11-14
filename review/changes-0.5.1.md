# What 0.5.1 changed

0.5.0 was the first version of this app that was used on a real phone for more than a try (14 November 2026).
Two things went wrong on that day that no test, emulator run or review had shown.

1. A wallet was to be brought back from a paper on which the words had not been copied right. For every kind
   of mistake the app had the same sentence ("These are not valid recovery words. Check the spelling and the
   order."), and it emptied the field. Each try meant typing all the words again without knowing more.
2. The first screen filled in the address of a public node that the project does not run yet. Pressing
   "Connect" there could only end with "The node cannot be reached".

The first of these is also a matter of the paper itself. When a wallet is made, the app asks back three of
the 24 words. A single word copied wrongly is noticed in one case out of eight.

| Change | Where | Tests |
|---|---|---|
| Typed words that are not valid are answered with what is wrong: the words that are not on the list, by their place and as they were typed (at most six, each cut to 16 characters), or the number of words, or that the words do not fit each other. What was typed stays in the field. | core: `Words.fault` in `Planner.kt`; app: `WalletModel.restoreWallet`, `paperLook`, `wordsDetail`, and `wordsProblemText` in `Parts.kt` | core `WalletEngineTest.whatIsWrongWithTypedWordsIsNamed`, `Retest4CoreTest`; app `WordsHelpTest.wordsThatAreNotValidAreAnsweredWithWhatIsWrongAndStayInTheField` |
| "Check the whole paper" (More): typed words are compared with the wallet in the phone. The stored words are not opened and the fingerprint is not asked for: the account key derived from the typed words is compared with the one in the wallet's public file. Words that match are taken out of the field; others stay to be put right. | `WalletModel.checkBackup`, `backupEdited`; `BackupBlock` in `Tabs.kt` | `WordsHelpTest.aPaperIsCheckedInFullAgainstTheWalletInThePhone` |
| A wallet that was just made is followed by the advice to check the paper in full before coins are sent to it. | `Notice.WALLET_MADE`, `keepWords(made)` | `WordsHelpTest.aWalletThatWasJustMadeIsFollowedByTheAdviceToCheckThePaperInFull` |
| Typed words now stay in their field after a check that failed, so they are taken away at the other end: when the page is left, and when the app is opened again after more than a minute in the background, also where the wallet does not lock. A message that names a typed word goes with them, and it goes when the field is emptied by hand. | `WalletState.typedEpoch`, `WalletModel.lock`, `typedWordsGone`, `restoreEdited`, `paperEdited`, `backupEdited`; `remember(state.typedEpoch)` and the `DisposableEffect` in `Tabs.kt` | `WordsHelpTest.typedWordsAndWhatWasSaidAboutThemGoWhenTheAppWasAwayOrThePageIsLeft`, `Retest4Test.aMessageThatNamesWhatWasTypedGoesWhenTheTextIsDeletedByHand` |
| Because the field keeps the words, the button that stores them could be tapped a second time while the first tap was still at work. A second store no longer starts beside the first, and a Keystore key is looked for and made in one step. (Found by the reviewer, R4-01; the button for a new wallet had allowed two taps since 0.3.1.) | `WalletState.walletBusy`, `WalletModel.keepWords`; `AndroidKeystoreKey.key` in `Store.kt` | `Retest4Test.aSecondTapOnStoreTheWalletLeavesWordsThatCanBeOpened`, `WordsHelpTest.aSecondTapWhileTheWordsAreBeingStoredIsIgnored` |
| The first screen names no public node while the project has none, says so, and puts the node of one's own first. | `PUBLIC_NODE` and `SetupScreen` in `MainActivity.kt` | `FirstScreenTest` |

Nothing changed in how keys are derived, how the words are sealed, how a payment is built and signed, or in
what is said to a node. What changed about storing the words is how often it can be started at once: once.

## What the check of the whole paper is not

It does not show the words, and it cannot mend a paper: it says whether the typed words are those of the
wallet in the phone, not which word is wrong. It needs the app to be open, and with it the More page shows
the wallet's public descriptor and the Receive page an address; somebody who knows most of the words learns
no more from the check than from those.

## How it was checked

- The independent reviewer looked at the change (`independent-review-0.5.1`): it does what it says; two low
  findings and three notes, all dealt with before the package was built again.
- The tests: 128, none failed, none skipped. Lint: 0 errors, 11 warnings, 2 hints. Both on the sources of the
  package (commit 6ec7a4c).
- The signed package (SHA-256 `1dbb549c1b801f0d6867e958671c18683a9c50b2941dc972c17b101ecc88c4ce`, version
  code 10) went through `static-checks.sh`; the output is in `evidence-0.5.1`. Permissions, components and
  libraries are those of 0.5.0. The code no longer holds the address of a node.
- The same package ran on emulators with Android 12, 14 and 16, five runs on each, and all passed. Among the
  steps are the first screen without a public node, the check of the whole paper, a wallet brought back after
  a slip in a word was named, and the upgrade from 0.5.0. `emulator/README.md` says what was run, and which
  three runs had to be repeated and why.
- Not checked: a real phone. That is the next step, on the phone where 0.5.0 showed the two faults.
