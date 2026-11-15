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

## What was checked against the real server

All of it reading, with addresses that are public chain data, on 15 November 2026:

- `review/standin/compare_pages.py`: 51 questions about pages of coins and of history and about what must be
  refused, to the stand-in and to the public server. Status, words, fields and page sizes were alike in all 51.
- `review/standin/check_sums.py`: for seven addresses that block rewards went to in October, the lines of the
  whole history added up to what the coins of the address hold.
- For four payments and one block reward of a mining pool's address, `net_amount` and `network_fee` were what
  the data of a TrueNorth Core node give for the same transactions.
- The client and the engine of 0.6.1 read the public server: the coins of three addresses over four pages
  (3,568 coins), 400 lines of history of one address with block rewards, payments and one transaction that
  waited for a block, a look at one watched address and at three together, and what arrived in thirty blocks.
  Every look was steady.
- The code of 0.6.0, asked the same at 19:23: the height and the coins were read, the history was refused
  (`received_amount`), and with it the whole look.
- The signed package of 0.6.1, installed on an emulator with Android 14, was given the public server and one
  address to watch that holds several thousand coins. It showed 33829.09039810 NORTH, the sum of the 3,353
  coins that the server listed for the address at block 25240 over four pages, and block rewards in its
  history. On the three emulators the package as installed read the height of the chain from the server.

## How it was checked

- The tests: 194, none failed, none skipped. Lint: 0 errors, 6 warnings, 2 hints. The full run on the sources
  of the package (commit 96d78d5) had 191 tests. The commit after it adds three tests of the look through a
  server and changes nothing in the app, and the full run was repeated with them. The tests about a server
  run against the stand-in on a real regtest node.
- Deliberate faults, one at a time, each to be caught by a test: 9 in the client for a server, 10 in the look
  through a server, 2 in the wallet's file and 2 in the model. Each was caught
  (`evidence-0.6.1/deliberate-faults.txt`). The three added tests are among those that caught them: the fee
  beside a loss, lines of two parts that do not fit together, and a transaction that arrives during a look.
- The signed package (SHA-256 `7da80705ad9c70ffc1ee4646d5f5d0bac86a707593476ac5214b1c3a420c914b`, version
  code 12) went through `static-checks.sh`; the output is in `evidence-0.6.1`. Permissions, components and
  libraries are those of 0.6.0, and the code holds the same one address of a server.
- The same package ran on emulators with Android 12, 14 and 16, eight runs on each, and all passed at the
  first try: the seven runs of 0.6.0, with the stand-in answering in the new shapes, and a new one. In the
  new one the signed package of 0.6.0 meets such a server, says that it cannot read the answer and shows no
  coins; then 0.6.1 is installed over it and shows the same wallet with its coins and history, the change of
  a waiting payment as on its way, and coins that come three to a page. `emulator/README.md` says what was
  run.
- Two runs on emulators read the public server (above).
- Not checked: what is listed under "What was not done", and 0.6.1 on a real phone.

## What 0.6.1 knows less well than 0.6.0 did

- How a movement is made up. A payment shows as what the wallet lost by it. What was received and what was
  spent in one transaction, and who was paid, are no longer in the server's lines; the app showed the sum and
  the fee before as well.
- The fee beside a payment is the fee of the whole transaction. It is shown when the wallet lost at least
  that much. Where coins of somebody else were spent in the same transaction, the wallet may have borne less
  of it than is shown.
- The change of a waiting payment that 0.6.0 wrote down, for the minutes until its block.

## What was not done

- The changes of 0.6.1 were checked by their author. The independent reviewer has not looked at them.
- No payment has been made through the public server from this app, on a phone or otherwise.
- 0.6.1 has not been on a real phone. It ran on emulators.
- A server that still answers in the earlier shapes is not read. None is known.
- Everything in one look has to come from one picture, and the server's picture now changes with every
  transaction that begins to wait anywhere on the network, not only with every block. On 15 November 2026
  that was about once in two minutes (thirty changes in fifty-six minutes), and a look takes seconds. On a
  busier network a long history, up to ten pages for each part of the addresses, could take longer to read
  than the picture holds. The app then tries three times and says that the server kept changing, or shows
  its last look as one that nothing is judged from. Nothing was done about that.
- The note under a history that is not whole is about the wallet's own history. The history of watched
  addresses with more lines than the app reads (2,000 for each hundred addresses) ends with the oldest line
  that was read and has no such note, as in 0.6.0.
- Nothing in an answer says which shapes a server speaks, so the next change of this kind will again be found
  by an answer that cannot be read. The author of the server has been asked whether a change could be made
  visible to a client.
