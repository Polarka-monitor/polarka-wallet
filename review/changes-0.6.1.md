# What 0.6.1 changed

On 15 November 2026 the public Northbound server changed two of its answers (github.com/nova9/northbound,
commits d72a5e1, e3376a1 and 66fa1d9; read here at 07523b6). The paths stayed as they were. The shapes did not:

- A line of history used to say what the addresses of the request received and what they sent and, where
  it could be told, the fee. It now says one signed amount, what the transaction paid to those addresses less
  what it took from them (`net_amount`), and beside it the fee of the whole transaction, whoever paid it
  (`network_fee`), which is missing for a block reward.
- Coins used to come in one answer, ten thousand at the most. They now come a page at a time, a thousand to
  a page unless the request asks otherwise, each page with a cursor to the next. A cursor holds for one block
  and one picture of the waiting transactions.
- The index names a number for its picture of the waiting transactions (`pending_revision`).

Version 0.6.0 was written for the earlier answers and refuses the new history as an answer in an unknown
form. A look reads the history every time, so a wallet or a watched address with any history could not be
shown through the public server at all: the app said that the server answered with an error. Keys and coins
were not touched by that, and a wallet connected to a node of its owner's was not affected. 0.6.1 reads the
new answers. It does not read the earlier ones.

| Change | Where | Tests |
|---|---|---|
| Coins are read page by page to the last cursor, for every part of the addresses. The size of a page is left to the server. When the server says that its index changed (409), or a page comes from another block or from other waiting transactions than the page before, everything read so far is thrown away and the reading starts again, three readings at the most. Half a list is never taken for a balance. | `ServerClient.utxos` | `ServerClientTest.coinsComeInPagesAndAreReadToTheLastOne`, `.partsReadFromDifferentPicturesAreNotPutTogether`, `StandInTest` |
| A line of history is read as a signed amount and the fee of the whole transaction. Refused: an amount that is no whole number or cannot exist, a fee below zero, a block reward that takes coins away or that waits for a block, and a line of the earlier form. | `ServerClient.move`, `.signed` | `ServerClientTest.historyIsReadPageByPageAndStartsAgainWhenTheIndexChanges` |
| The picture an answer was read from is the block and the server's number for the waiting transactions. Parts and pages of one question have to agree in both, and so have the questions of one look; a look across a change is made again. A server that names no such number is compared by the block alone. | `ServerClient.moment`, `ServerEngine.at`, `.fetch` | `ServerClientTest.partsReadFromDifferentPicturesAreNotPutTogether`, `ServerEngineTest.aTransactionThatArrivesDuringALookMakesItBeMadeAgain`, `.aWalletWithMoreAddressesThanOneRequestTakes` |
| The history of a wallet with more than a hundred addresses is exact. The lines that the parts of the addresses give for one transaction add up to what it did to the wallet. In 0.6.0 a payment with coins in two parts lost its fee, and one of which a part got back more than it put in was left out. Lines for one transaction that name different blocks or different fees are not added up: the movement is left out, and the history says that it is not whole. | `ServerEngine.movement`, `.sumOf` | `ServerEngineTest.transactionsWhoseKeysSitInTwoPartsOfTheAddresses`, `.linesOfTwoPartsThatDoNotFitTogetherAreNotShownAsAMovement`, `StandInTest` |
| A payment shows as what the wallet lost by it. The fee of the whole transaction stands beside it only when the wallet lost at least that much, and so can have paid it. | `ServerEngine.movement` | `ServerEngineTest.theFeeStandsBesideALossOnlyWhenTheWalletCanHavePaidIt` |
| A block reward is known by having no fee, also after its coin was spent. The Mining page counts the rewards of the whole history that the app reads. | `ServerEngine.movement`, `Tabs.kt`, `strings.xml` | `ServerEngineTest.coinsHistoryAndAWaitingPaymentAsANodeWouldShowThem` |
| What a waiting payment of the wallet's own gives back. The server shows such a payment as a loss, and its coins are gone from the list at once; the change is in no answer. A payment is now written down with what its coins held together, and the change is worked out from that and the server's line. The note is kept in the wallet's file and stays with a payment that goes to those that did not go through and comes back. A payment written down by 0.6.0 has no note, and its change shows with its block. | `Pending.inputSat`, `Dropped.inputSat`, `WalletInfo.toJson`, `.parse`, `WalletModel`, `ServerEngine.arriving` | `WhatAPaymentPutInTest`, `ServerEngineTest.coinsHistoryAndAWaitingPaymentAsANodeWouldShowThem`, `.transactionsWhoseKeysSitInTwoPartsOfTheAddresses`, `ServerModelTest`, `SixthLookScreenTest.aPaymentThatComesBackFromThoseThatDidNotGoThroughKeepsItsTime` |
| What arrived since a block, for the notices: every transaction in a block that left the wallet, or the watched addresses, with more than they had. In 0.6.0 a payment to one of the wallet's change addresses was not told. | `ServerEngine.since` | `ServerEngineTest.whatArrivedSinceABlock`, `.aWalletWithMoreAddressesThanOneRequestTakes` |
| At most 20,000 coins are read for a wallet through a server. With more, the app says so and shows no balance. The server's own bound of 10,000 coins, and its refusal, are gone. | `ServerClient.MAX_COINS`, `.TOO_MANY`, `ServerEngine.asked` | `ServerClientTest.coinsComeInPagesAndAreReadToTheLastOne`, `SixthLookTest.moreCoinsThanAreReadThroughAServerAreCalledThat` |
| The note under a history that is not whole names the two reasons that are left: a history longer than the app reads, and lines of the server for one movement that do not fit together. | `strings.xml` (en, cs) | `HistoryNoteTest` |
| The stand-in for tests answers in the new shapes, with a switch for the size of a page. | `review/standin/northbound_standin.py` | `StandInTest`, and every test of the wallet over a server |

## What the independent look changed

An independent reviewer looked at 0.6.1 on 16 November 2026, before anything of it was handed out
(`independent-review-0.6.1/`). It gave no safety reason to hold the release back. It had one finding of
medium severity, about a look while the server's picture of the chain keeps moving, three low ones and four
notes. These changes followed, before the package of the release was built:

| Change | Where | Tests |
|---|---|---|
| A list of coins or a history that a change of the picture cut in each of its three readings no longer ends the look with an error. The client refuses it as something to ask for again, like activity read across a change, and the look is made again: three looks at the most, and none begun later than a minute and a half after the first. When no look comes to its end, the app says that the picture of the chain kept changing while it was read and shows nothing new. Before, it said after one look that the server answered with an error. | `ServerClient.utxos`, `.history`, `.MOVED`, `ServerEngine.asked`, `.fetch`, `Problem.Kind.MOVING`, `Parts.kt`, `strings.xml` (en, cs) | `ServerClientTest.aListThatKeptChangingIsRefusedAsOneToAskForAgain`, `.aReadingThatMeetsABusyServerAndThenANewPictureStartsAgain`, `ServerEngineTest.aListThatAMovingPictureKeptCuttingIsLookedAtAgain`, `AfterSeventhLookTextsTest` |
| A list in pages is not followed without end. Coins: at most 200 pages beside one for every part of the addresses; the real server gives a thousand coins to a page. History: at most twice the pages that the lines read for one part take when the pages are full, and two more (22 for 2,000 lines); a server that hands out less to a page gets its history cut short, and the history says that it is not whole. The reading of one list also has a time limit as a whole, two minutes with its new starts in it. Before, a server that gave one coin to a page was asked 20,001 times for one list. | `ServerClient.utxos`, `.history`, `.MAX_COIN_PAGES`, `.READ_MS` | `ServerClientTest.aServerThatHandsOutALittleAtATimeIsNotFollowedWithoutEnd`, `.aListInPagesIsNotReadForLongerThanItsTime` |
| The history of watched addresses says when it is not whole, as the wallet's own does: when it is longer than the app reads, when a part of the addresses was cut short and may hold a line for a movement, and when the lines for one movement do not fit together. The note on the History page is the same one for both. | `ServerEngine.look`, `Snapshot.historyWhole` | `ServerEngineTest.aHistoryOfWatchedAddressesThatIsCutShortSaysSo`, `.linesForWatchedAddressesThatDoNotFitTogetherSayThatTheHistoryIsNotWhole` |
| The message about more coins than are read writes the bound with its thousands apart. | `Parts.kt` | `AfterSeventhLookTextsTest.theBoundOfCoinsIsWrittenWithItsThousandsApart` |

The reviewer looked at these changes on the same day (commit 5d923fd). It found the bound of pages, the
history of watched addresses and the texts in order, and the first finding eased as far as the client alone
can do it: a look that a moving picture cuts is made again, but the whole history is still read on every
look (see "What was not done"). It had three notes more, and these changes followed:

| Change | Where | Tests |
|---|---|---|
| A reading whose time runs out after a change of the picture has already cut it is refused as a picture that kept moving, like one that was cut three times. Before, the app said that the server did not answer in time. | `ServerClient.cutShort`, `.utxos`, `.history` | `ServerClientTest.aListInPagesIsNotReadForLongerThanItsTime` |
| The words for a picture that kept changing fit the payment screen as well, where the coins are read for a payment: nothing new is shown, and no payment is worked out. | `strings.xml` (en, cs) | `AfterSeventhLookTextsTest.aPictureThatKeptChangingIsSaidAsThatAndNotAsABusyServerOrAnError` |
| A test for the one branch that had none: lines of two parts of the watched addresses that do not fit together. | | `ServerEngineTest.linesForWatchedAddressesThatDoNotFitTogetherSayThatTheHistoryIsNotWhole` |

None of the changes after the independent look touches the safeguard against paying the same thing twice,
the reading of an amount or the wallet's file.

## What was checked against the real server

All of it reading, with addresses that are public chain data, on 15 November 2026, with the code of that day
(commit 96d78d5) and the first package built from it:

- `review/standin/compare_pages.py`: 51 questions about pages of coins and of history and about what must be
  refused, to the stand-in and to the public server. Status, words, fields and page sizes were alike in all 51.
- `review/standin/check_sums.py`: for seven addresses that had received block rewards and nothing else, and
  had spent nothing, the lines of the whole history added up to what the coins of the address hold. That
  shows nothing about lines below zero, about fees, or about a history put together from parts.
- For four payments and one block reward of a mining pool's address, `net_amount` and `network_fee` were what
  the data of a TrueNorth Core node give for the same transactions. Each was one request.
- That the lines of several parts add up to what a transaction did to the whole wallet was not checked on
  the public server. It rests on the server's sources and on the tests against the stand-in on a regtest
  node.
- The client and the engine of 0.6.1 read the public server: the coins of three addresses over four pages
  (3,568 coins), 400 lines of history of one address with block rewards, payments and one transaction that
  waited for a block, a look at one watched address and at three together, and what arrived in thirty blocks.
  Every look was steady.
- The code of 0.6.0, asked the same at 19:23: the height and the coins were read, the history was refused
  (`received_amount`), and with it the whole look.
- The first signed package of 0.6.1, installed on an emulator with Android 14, was given the public server and one
  address to watch that holds several thousand coins. It showed 33829.09039810 NORTH, the sum of the 3,353
  coins that the server listed for the address at block 25240 over four pages, and block rewards in its
  history. On the three emulators the package as installed read the height of the chain from the server.

## How it was checked

A first package of 0.6.1 was built on 15 November 2026 from commit 96d78d5 (SHA-256
`7da80705ad9c70ffc1ee4646d5f5d0bac86a707593476ac5214b1c3a420c914b`). It went through the tests (194 at that
time), 23 deliberate faults, `static-checks.sh` and twenty-four runs on emulators with Android 12, 14 and
16, and it read the public server from an emulator; `evidence-0.6.1` holds what that gave. It was handed to
nobody. The independent look came after it and changed the code, so the package of the release is built
from the commit that the tag `v0.6.1` names, and the checks are made again on that package. The commit after
that one writes here, and into `evidence-0.6.1`, what they gave.

## What 0.6.1 knows less well than 0.6.0 did

- How a movement is made up. A payment shows as what the wallet lost by it. What was received and what was
  spent in one transaction, and who was paid, are no longer in the server's lines; the app showed the sum and
  the fee before as well.
- The fee beside a payment is the fee of the whole transaction. It is shown when the wallet lost at least
  that much. Where coins of somebody else were spent in the same transaction, the wallet may have borne less
  of it than is shown.
- The change of a waiting payment that 0.6.0 wrote down, for the minutes until its block.

## What was not done

- A review by an independent human expert. The look at 0.6.1 was made by an AI model, like the six before
  it, and it did not cover the build of the package, the runs on emulators or anything on a phone
  (`independent-review-0.6.1/`).
- No payment has been made through the public server from this app, on a phone or otherwise.
- 0.6.1 has not been on a real phone. It ran on emulators.
- A server that still answers in the earlier shapes is not read. None is known.
- A long history through a busy server. Everything in one look has to come from one picture, and the
  server's picture changes with every transaction that begins to wait anywhere on the network, not only
  with every block. On 15 November 2026 that was about once in two minutes (thirty changes in fifty-six
  minutes). After every change the server answers 503 for a moment, and the client waits two seconds before
  it asks again. Every look reads the whole history again, up to ten pages of 200 lines for each part of
  the addresses, and a page took about a second from the public server (4,000 lines in twenty seconds, read
  from a computer). A reading that a change cut starts again, three readings at the most, and then the look
  is made again, three looks at the most. Worked out from the figures of that day, and not measured: a reading
  of ten seconds is cut about one time in twelve, and three in a row rarely. On a network ten times as busy
  such a reading is cut more often
  than not, and a wallet with a long history, one that is mined to for example, will often get no look of
  one picture. The app then shows a look that nothing is judged from or, when no look came to its end, says
  that the picture kept changing and shows nothing new. Coins are not touched by that. What would mend it
  was not done: keeping the lines that are in blocks from one look to the next and reading only what is
  new. The client alone cannot make a long reading safe, because a cursor of the server holds for one
  picture only.
- How long a look through the real server takes was not measured on a phone. The stand-in is fast: it
  answers 503 only when a test tells it to, and it takes no second for a page.
- One note stands for both histories. It does not say whether the wallet's own history or that of the
  watched addresses is the one that is not whole.
- A look whose parts are not of one picture is shown without a mark on the screen, as in 0.6.0. Nothing is
  judged from it.
- A transaction made elsewhere with the same words is known by a waiting line below zero that is none of
  the wallet's own payments. One that spends a coin of the wallet and still leaves every part of the
  addresses with more, which takes coins of somebody else in the same transaction, is not seen as that, and
  a missing coin of an unanswered payment is then not called tied up. The wallet still makes no payment
  while such a payment has no coin among those the server lists.
- The amount on its way counts the change of a waiting payment of the wallet's own also when another
  waiting transaction already spends that change, and it does not count the change of a transaction made
  elsewhere. It is shown apart from what can be spent, and nothing is decided from it.
- Nothing in the answers that 0.6.1 reads says which shapes a server speaks, so the next change of this kind
  will again be found by an answer that cannot be read. The author of the server was asked whether a change
  could be made visible to a client, and on 16 November 2026 he added `GET /info`, which names the release of
  the server (github.com/nova9/northbound, issue 5; the public server did not have it yet on the morning of
  that day). 0.6.1 was finished before that and does not ask it.
