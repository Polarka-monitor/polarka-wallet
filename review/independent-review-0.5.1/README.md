# The fifth look of the independent reviewer (0.5.1)

`RETEST4.md` in this folder is the report of a fifth, short look by the same independent reviewer, made on
14 November 2026. It covers what 0.5.1 changed: commit 98a2b77 against 79cec8f, which is the 0.5.0 that had
been installed on a phone. The report is unchanged. The reviewer is an AI model that had not seen the code
being written; this is not a review by an independent human expert.

The reviewer found that the change does what it says, nothing of medium or high severity, two low findings
and three notes. The package that had been built from 98a2b77 was not handed to anybody. What the report names
was dealt with before 0.5.1 was built again, as the table says.

## What was done

| Finding | What was done | Tests |
|---|---|---|
| R4-01 "Store the wallet" can be tapped twice, and two stores can leave words that cannot be opened | A second store does not start while one is under way: `WalletModel.keepWords` returns at once, and the buttons that start a store are off meanwhile (`WalletState.walletBusy`). That holds for "Store the wallet" and for "Save" on the screen of a new wallet, where two taps had been possible since 0.3.1. `AndroidKeystoreKey.key` now looks for a key and makes it in one step for the whole app, so that a second caller finds the first key instead of making another. | the reviewer's `Retest4Test.aSecondTapOnStoreTheWalletLeavesWordsThatCanBeOpened`; `WordsHelpTest.aSecondTapWhileTheWordsAreBeingStoredIsIgnored` |
| R4-02 the README said that typed words go when the page is left; the words of a paper address that was looked at do not | The README says what the app does: such a look stays, also on other pages, until the address is emptied, the look is dropped, or the app is opened again after a minute or more in the background. The app was not changed: its owner may look something up on another page and come back to empty the address. | the reviewer's test, turned round: `Retest4Test.aPaperAddressThatWasLookedAtStaysOpenOnOtherPagesAndGoesAfterAMinuteAwayOrWhenDropped` |
| N4-1 a message that names what was typed stays when the text is deleted by hand, and it can hold real words | The message goes when the field is emptied, in all three fields. While there is text in the field it stays: it says which word to put right, and it shows nothing that the field above it does not show. The reviewer proposed to take it away at any change; that would take the hint away at the first keystroke of the correction. | the reviewer's `Retest4Test.aMessageThatNamesWhatWasTypedGoesWhenTheTextIsDeletedByHand`; `WordsHelpTest` (first and third test) |
| N4-2 "after a minute in the background" happens when the app comes back, not by a clock | The README and both guides say "when the app is opened again after a minute or more in the background". | |
| N4-3 the app said "a different word than on your paper", and it cannot know the paper | The sentence for words that do not fit each other speaks of the right word, and adds that the paper was copied wrongly if what was typed is what the paper says. The answer for valid words of another wallet adds that a paper with one wrong word can look like this. The guides say what an empty wallet after bringing back can mean. | |

The reviewer's two test files are in the suite as `Retest4Test.kt` and `Retest4CoreTest.kt`. SHA-256 of the
reviewer's own files: `e7f123f8075b58f958b483e3a9a079988e51b6b79eb7a791931d4a70ea30fa54` and
`8025d072a72c458c6d01d4adda87d0d60f9f18534d5e181c8ce8deab5b055e9f`. The core file is unchanged. In
`Retest4Test.kt` the comments say what was done about each finding, and the second test was turned round as
the table says; the first and the third test are the reviewer's.

## What the reviewer could not check

As the report says: nothing on a phone or an emulator, not the signed package, and not what the Keystore of a
real phone and its dialog do when "Store the wallet" is tapped twice. The finding R4-01 was shown with a
stand-in for the Keystore. The two changes close the way in the app's own code; how a given phone behaved
before them was not tried.
