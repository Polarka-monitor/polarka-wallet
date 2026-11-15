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
| The picture an answer was read from is the block and the server's number for the waiting transactions. Parts and pages of one question have to agree in both, and so have the questions of one look; a look across a change is made again. A server that names no such number is compared by the block alone. | `ServerClient.moment`, `ServerEngine.at`, `.fetch` | `ServerClientTest.partsReadFromDifferentPicturesAreNotPutTogether`, `ServerEngineTest.aWalletWithMoreAddressesThanOneRequestTakes` |
| The history of a wallet with more than a hundred addresses is exact. The lines that the parts of the addresses give for one transaction add up to what it did to the wallet. In 0.6.0 a payment with coins in two parts lost its fee, and one of which a part got back more than it put in was left out. | `ServerEngine.movement`, `.sumOf` | `ServerEngineTest.transactionsWhoseKeysSitInTwoPartsOfTheAddresses`, `StandInTest` |
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
- A server that still answers in the earlier shapes is not read. None is known.
- Nothing in an answer says which shapes a server speaks, so the next change of this kind will again be found
  by an answer that cannot be read. The author of the server has been asked whether a change could be made
  visible to a client.
