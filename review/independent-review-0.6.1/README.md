# The seventh look of an independent reviewer (0.6.1)

`REVIEW-0.6.1.md` in this folder is the report of a look at what 0.6.1 changed, made on 16 November 2026,
before anything of 0.6.1 was handed out. It covers the three commits after 0.6.0 (96d78d5, 6fc25ea and
d4a89e6, after 3b5e36a), read beside the sources of the Northbound server at 07523b6. The report is as the
reviewer wrote it. The reviewer is an AI model that had not seen the code being written. It is not the
reviewer of the six earlier looks continued: it began afresh, with this repository and the earlier reports
in it, a brief of questions, and the four texts that were to go out with the release. This is not a review
by an independent human expert.

The reviewer found nothing that lets an honest server make the wallet show an untrue line of history, and
nothing that weakens the safeguard against paying the same thing twice. It would not have held the release
back for a safety reason. It had one finding of medium severity, three low ones and four notes, and it found
the author's own account of one weakness not right. Its advice was to hold the release for the texts, and to
mend the medium finding before the release if that could be tested the same day. A first package of 0.6.1
existed by then, built from 96d78d5; it had been handed to nobody. The package of the release was built
after the changes below.

## What was done

| Finding | What was done | Tests |
|---|---|---|
| R1 (medium). When the server's picture of the chain changed during each of the three readings of a list of coins or of a history, the look failed at once, with an error and no figures: the engine made its look again only after activity that was read across a change. The author's account said that the app would try three times, or show its last look as one that nothing is judged from. The picture changes with every transaction that begins to wait anywhere on the network, every look reads the whole history again, and a long history takes many seconds, so on a busier network this would be common. It is about availability, not safety: nothing is judged from a look that fails. | The client refuses such a list as something to ask for again, and the engine makes its look again: three looks at the most, none begun later than a minute and a half after the first. When no look comes to its end the app says in words of its own that the picture kept changing; it no longer says that the server answered with an error, or that blocks kept arriving. What arrived since a block, for the notices, is told the same way. `ServerClient.utxos`, `.history`, `.MOVED`; `ServerEngine.asked`, `.fetch`, `.AGAIN_MILLIS`; `Problem.Kind.MOVING`; `problem_moving_server` (en, cs). `changes-0.6.1.md` and the limits in the README say what the code does. Not done: the reviewer's second advice, to keep the lines that are in blocks from one look to the next and read only what is new. The whole history is still read on every look, so a long history through a busy server can still fail to load; it now fails later, and the app says why. | `ServerClientTest.aListThatKeptChangingIsRefusedAsOneToAskForAgain`; `ServerEngineTest.aListThatAMovingPictureKeptCuttingIsLookedAtAgain`; `AfterSeventhLookTextsTest.aPictureThatKeptChangingIsSaidAsThatAndNotAsABusyServerOrAnError` |
| R2 (low). Nothing bounded the number of pages of one reading. A server that gave one coin to a page was asked 20,001 times for one list of coins and 2,000 times for one history, each question with ninety seconds of its own, while the engine's lock was held. | The pages of one reading are bounded: for coins 200 beside one for every part of the addresses (the real server gives a thousand to a page), for history twice the pages that the lines read take when they are full, and two more (22 for 2,000 lines), after which the history ends where it is and says that it is cut short. The reading of one list has a time limit as a whole, two minutes with its new starts in it. A look as a whole has no limit of its own: it is a known number of such readings and of single questions, each with its limit. `ServerClient.utxos`, `.history`, `.MAX_COIN_PAGES`, `.READ_MS`. | `ServerClientTest.aServerThatHandsOutALittleAtATimeIsNotFollowedWithoutEnd`, `.aListInPagesIsNotReadForLongerThanItsTime` |
| R3 (low). The history of watched addresses dropped movements without a word: when it was longer than the app reads, which the author had named, and also when a part of the addresses was cut short and a line might be missing, and when the lines of two parts did not fit together. The Mining page counts rewards from that history as well. | The look says that the history is not whole in all three cases, for watched addresses as for the wallet's own, and the History page shows its note. One flag and one note stand for both histories, where the reviewer advised a flag of its own: the note does not say which of the two is not whole. `ServerEngine.look`. | `ServerEngineTest.aHistoryOfWatchedAddressesThatIsCutShortSaysSo` |
| R4 (low). Texts that said more than the code or the evidence. (1) The account of a busy network, and the message "blocks kept arriving". (2) The check that the lines of a history add up to the coins was made on addresses with block rewards only, and two texts described those addresses differently. (3) The note for the author of the server named `tip_hash` where the client compares the block and the number of the `index`. (4) "194 tests, none skipped" holds only where the gateway of polarka-node is present. (5) The bound of coins was printed without its thousands apart. | (1) See R1. (2) `changes-0.6.1.md` says what the addresses were, that the check shows nothing about losses, fees or parts, and that the sum over several parts rests on the server's sources and on tests against the stand-in. (3) That note and the other texts for the release are not part of this repository; they were rewritten. (4) The README says so, and the release notes now say it as well. (5) The bound is written as the reader's language writes a number, "20,000" in English. | `AfterSeventhLookTextsTest.theBoundOfCoinsIsWrittenWithItsThousandsApart` |

## The notes

| Note | What was done |
|---|---|
| N1. The stand-in answers 503 only when it is told to, and it is fast. No test showed a reading that meets a 503 in its middle and then a new picture, and the duration of a look was tested nowhere. | The client has a test for a reading of coins and one of history that meet a 503 and then a refused cursor (`ServerClientTest.aReadingThatMeetsABusyServerAndThenANewPictureStartsAgain`). The time that looks may take is tested with a clock of the test's own (`ServerEngineTest.aListThatAMovingPictureKeptCuttingIsLookedAtAgain`) and with a slow server (`ServerClientTest.aListInPagesIsNotReadForLongerThanItsTime`). The stand-in is as it was, and how long a look through the real server takes on a phone was not measured. |
| N2. A transaction made elsewhere with the same words is now known by a line below zero. One that spends a coin of the wallet and leaves every part of the addresses with more is no longer seen as that; the safeguard holds all the same. | Left as it is, and written down under "What was not done" in `changes-0.6.1.md`. |
| N3. "On its way" can count change that another waiting transaction already spends. | Left as it is, as after the sixth look, and written down in the same place. The figure is shown apart from what can be spent, and nothing is decided from it. |
| N4. What a server gets from the number of its picture: nothing by never changing it. By changing it with every answer it keeps every look unsteady, which is a power it had before. An unsteady look is shown without a mark on the screen. | Left as it is, and written down in the same place. |

## The reviewer's own tests

`ReviewerPagesTest.kt` and `ReviewerBusyTest.kt` in this folder are the reviewer's tests as they were handed
over. Each asserts a fault, so that it passed while the fault was there. Against the sources after the
changes they fail at those assertions:

    ReviewerPagesTest.oneCoinToAPageIsFollowedForMoreThanTwentyThousandRequests
        201 requests where there were 20,001, ended by the bound of pages and not by the bound of coins
    ReviewerPagesTest.oneLineToAPageIsFollowedForTwoThousandRequestsPerPart
        22 requests where there were 2,000; the history says that it is cut short
    ReviewerBusyTest.aHistoryOfTwoPagesOnAMovingPictureFailsTheWholeLook
        the look is made again and ends as a picture that kept changing, not as an error of the server

They are not part of the suite. The tests named in the tables tell the same stories and assert the opposite.
`reviewer-node.log`, `reviewer-app.log` and `all-summary.txt` are the reviewer's logs of its two tests and
of the whole suite on its machine: 194 tests, one of them skipped there for want of the gateway of
polarka-node. The log of the reviewer's one deliberate fault is not in this folder because it holds paths of
the machine; the report says what it showed.

SHA-256 of the reviewer's files as they were handed over:

    b64fd300ce0b1bb2785533918ae652ca68cc50f48cedd1fd83822f01418756e7  REVIEW-0.6.1.md
    dd7dac71c7e658399bf96f33eca82e0b14b0fc3ea5fc8d1c0df291c05595fc3a  ReviewerPagesTest.kt
    0edc81c01e09978691d6861d6818e1ada25b7263469ddf12060b9508260d479d  ReviewerBusyTest.kt
    f1698c91592f5fb6bcad4fe4baedda1482e2b300a1621d67431d4459cd2a4d13  reviewer-node.log
    ea53fb667848aa25e1f5af544e4e25019114e038b39e3bad6a55fbcfac59b272  reviewer-app.log
    0e089de14e50dd3270828349cc81127f2ac0c701778c58ee64ef038e51cb0f31  all-summary.txt
