# What 0.6.0 changed

Up to 0.5.1 the wallet needed a TrueNorth Core node: one's own, or a public one behind the gateway of
`polarka-node`, which nobody runs. For somebody without a node the app ended at its first screen.

0.6.0 lets the wallet work through a wallet server instead. Northbound (github.com/nova9/northbound) is an
independent wallet for TrueNorth whose server stands in front of a node and keeps an index of addresses. Its
public server is named in the app at its author's request (`https://api.northboundwallet.org`). The first
screen offers that server, says whose it is and what it sees, and takes another address in its place. A node
of one's own stays as it was.

The second change is the owner's choice of 24 or 12 recovery words for a new wallet.

The third came out of the review of the first two and reaches further than the server: a payment that was
shown to anybody but the owner's own node is no longer forgotten on that party's word. The reviewer then
looked at the fixes, twice; what those looks left open is in the table as well.

| Change | Where | Tests |
|---|---|---|
| A client for a wallet server with the Northbound API, version 1. https only (plain http to a private address), no redirects, limits on every answer; whole numbers that can exist, ids that look like ids, a coin whose depth fits its block. An index that is not at the tip of its node is not believed. Coins and activity are asked for a hundred addresses at a time and put together only when every part came from the same block. A server that asks to slow down (429) or is busy (503) is waited for, twenty seconds at most. | node: `ServerClient.kt` | node `ServerClientTest` |
| The questions of one look go over one connection: an answer is read to its end, so the connection stays open for the next question. | `ServerClient.once` | `ServerClientTest.theConnectionIsLeftOpenForTheNextQuestion` |
| A question has a time limit as a whole (ninety seconds, or less where less is asked for), beside the limits for a connection and for one read. The wait for its turn counts against that time: one question at a time goes to a server, in the order of asking. A question is ended once, by its reader or by the clock, so the clock does not close a connection whose answer was read to its end. | `ServerClient.once`, `call` | `ServerClientTest.anAnswerThatComesALittleAtATimeIsNotWaitedForWithoutEnd`, `aQuestionDoesNotWaitForItsTurnLongerThanItsTime` |
| The wallet's look at the chain through a server: coins, the keys in use, the history, what became of the wallet's waiting payments and of those that did not go through, watched addresses, and what arrived since a block (for the notification). Every coin must sit on one of the wallet's own keys. Everything in one look must come from the same block, or the look is made again. | app: `ServerEngine.kt` | `ServerEngineTest`, `StandInTest` |
| A wallet with more addresses than one request takes is asked about in parts, fifty keys of each kind to a part, and the lines the parts give for one transaction are put together. A line that cannot be told is left out and the history says that it is not whole. | `ServerEngine.histories`, `movement`; `Snapshot.historyWhole`; `HistoryTab` | `ServerEngineTest.aWalletWithMoreAddressesThanOneRequestTakes`, `transactionsWhoseKeysSitInTwoPartsOfTheAddresses`, `aPartOfTheAddressesWhoseHistoryIsCutShort`; `HistoryNoteTest` |
| Nothing is concluded from a coin that a server does not list. A payment of the wallet's counts as overtaken only when the server shows, in a block, another payment of this wallet that spends one of its coins. | `ServerEngine.judge` | `SixthLookTest.aCoinThatAServerLeavesOutDoesNotUndoTheGuard`, `aPaymentThatDidNotGoThroughIsKeptWhileTheOneBuiltOnItsCoinOnlyWaits`; `ServerEngineTest.aPaymentThatWaitsOnTheCoinOfOneThatDidNotGoThrough` |
| Paying through a server. The payment is signed in the phone and written down before anything leaves it. The node behind the server is first asked whether it would take the transaction; then the transaction is handed over. A failed hand-over says nothing by itself (the server answers every one alike), so the server is asked whether it knows the transaction and the same transaction is handed over again, three times at most; if nobody knows, the payment stays "outcome not known" with its coins set aside until a look settles it. An answer that names another transaction is not believed. The whole lasts about a minute at most. | `ServerEngine.plan`, `sign`, `deliver` | `ServerPayTest`, `ServerModelTest.aRefusalAndAHandOverWhoseOutcomeNobodyTells` |
| A payment that a wallet server or a public node refuses is kept as one that did not go through, and the next payment is built on one of its coins. Only the owner's own node is asked before a payment is written down, and only what that node refuses is forgotten. A public node is shown a payment in the hand-over and no longer at the signing. | `Gateway.ownersNode`; `Engine.sign`, `Engine.deliver`; `ServerEngine.deliver`; `WalletModel.sendNow`; `Problem.Kind.REFUSED_KEPT` | `SixthLookTest.aPaymentThatAServerRefusedIsNotForgotten`, `aPublicNodeSeesAPaymentOnlyWhenItIsWrittenDownAndItsNoIsNotForgotten`, `theOwnersNodeIsAskedBeforeAnythingIsWrittenDownAndItsNoIsTheEnd` |
| The same transaction refused a second time is kept once. With no room left among the payments that did not go through (there is room for fifty), a refused payment stays with its outcome open, and the screens then say what is true of it: a look that finds the payment waiting settles it, and otherwise room has to be made first. | `WalletModel.sendNow`, `outcomeOpen`; `Problem.Kind.REFUSED_NO_ROOM`, `NO_ROOM`; `Tabs.kt` | `SixthLookScreenTest.theSameTransactionRefusedTwiceIsKeptOnce`, `withNoRoomLeftARefusedPaymentIsNotPromisedWhatALookCannotDo` |
| A payment that has waited for a block for over an hour is named on the Overview and where the next payment is confirmed: a payment made meanwhile does not stand against it, and whoever was handed it can hold it back. The hour counts from when the payment was made, also when it was among the payments that did not go through meanwhile and came back. | `Pending.waitsLong` in `Store.kt`; `Tabs.kt`; `WalletModel.settle` | `AfterSixthLookPlainTest`; `SixthLookScreenTest.aPaymentThatHasWaitedLongIsNamedBeforeTheSameThingIsPaidAgain`, `aPaymentThatComesBackFromThoseThatDidNotGoThroughKeepsItsTime` |
| A refused emptying of a paper address is called nothing only when the owner's own node refused it. After a no from a server or a public node the owner is told that the transaction was seen, that it could still be passed on while the coins of the address are unspent, and what to do. | `paperRefusal` in `WalletModel.kt`; `Problem.Kind.PAPER_REFUSED` | `AfterSixthLookPlainTest`; `SixthLookScreenTest.aRefusedEmptyingOfAPaperAddressIsNotCalledNothing` |
| One face for the node and the wallet server: what the model and the notifier use of either is one interface. | `Gateway.kt`; `Engine.kt`, `ServerEngine.kt`; `WalletModel.kt`, `Notify.kt` | the suite of 0.5.1 passes; `ServerModelTest.theNotifierAsksTheServer` |
| A setup can name a wallet server: the third form, `polarka-setup/3`, with the server's address and nothing secret. A text that gives a server an account, or names a node and a server, is refused. | `Setup.kt` | `SetupTest.aWalletServerIsNamedByItsAddressAlone` |
| A server of another network refuses the wallet's addresses, and that is how it is known: the app says so with the server's own words. A server's refusal to list more than 10,000 coins is said as what it is. | `ServerEngine.asked`; `Problem.Kind.OTHER_CHAIN`, `TOO_MANY_COINS` | `ServerModelTest.aServerOfAnotherNetworkAndOneThatIsNotThere`; `SixthLookTest.moreCoinsThanAServerListsAreCalledThat` |
| The first screen offers a wallet server first, with the Northbound server filled in, says that it is not run by the authors of this app and what a server sees and could do, and puts the node of one's own below. The block for a public node is gone from the first screen; a setup file for one is still taken. | `SetupScreen` in `MainActivity.kt`; `WalletModel.offerServer` | `FirstScreenTest`, `ServerScreenTest` |
| Where the wallet asks a server, the screens say "server": problems, notices, waiting payments, the More page, the block for a paper address. An amount that waits for a block on its way to the wallet is shown apart ("On its way, waiting for a block"). The mining page says that a server tells no hash rate. | `LocalOverServer` and `pick` in `Parts.kt`; `Tabs.kt`; both `strings.xml` | `ServerScreenTest`, `SixthLookScreenTest` |
| A new wallet has 24 words, or 12 if its owner chooses so. A wallet that exists is not touched; one is brought back from 12 or 24 words as before. | core: `Words.generate` in `Planner.kt`; `WalletModel.startNewWallet`; `NoWallet` in `Tabs.kt` | core `WordsCountTest`; `ServerScreenTest.aWalletOfTwelveWordsOverAServerAndWhatThePagesSay` |
| A stand-in for a Northbound server, for tests: the same requests and answers from a regtest node. Written from the server's sources, which were read and not built or run, and compared with the public server by reading. | `review/standin` | `StandInTest`; `review/standin/README.md` |

Nothing changed in how keys are derived, how the words are sealed, or how a payment is built and signed. A
wallet made with an earlier version stays where it is, with its node, and a node of one's own is asked what
it was asked before.

## What a wallet server learns and what it can do

It is told every address the wallet looks at, a hundred to a request, from the same phone and one request after
the other, so it can tell that they belong together; and it sees the phone's internet address. The same goes
for watched addresses and for a paper address that is emptied through the app. It sees every payment when it
is handed over.

It can show a false balance or history, keep an incoming payment from its owner, hold a payment back, or call
a payment done that is not. It cannot spend, change who is paid or how much, or make the wallet overpay a fee
by lying about a coin: the signature covers the amounts of the coins it spends, so a transaction built on a
wrong amount is not valid (`ServerPayTest.aServerThatLiesAboutACoin`, against a real node).

It cannot make the wallet pay the same thing twice by saying no to a payment, by not knowing it, or by leaving
a coin out of its list. That is what the review found possible and what was put right; `SixthLookTest` shows
it.

Two ways to a second payment remain, and both lead through the owner's own hand. A server that never lists
the coin of a payment that did not go through stops every payment until the owner forgets the entry; the
entry says to look at the wallet through another server or a node first
(`SixthLookScreenTest.aCoinThatStaysHiddenStopsEveryPaymentUntilItsOwnerForgetsTheEntry`). And a server that
holds a payment back while it calls it known keeps it "waiting for a block". A payment made meanwhile does not
stand against a waiting one, so an owner who pays the same thing again can pay twice. After an hour of waiting
the app says so, on the Overview and where the next payment is confirmed. The same holds for a public node,
and the second also for a node of one's own whose payment is slow to reach a block.

A paper address that is emptied through a server is no different: a server that says no has seen the
transaction. Nothing of it is kept in the phone (the words are not stored), so the screen says what follows:
the address is to be emptied again, and until it is empty the same thing is not to be paid in another way.

The fee rate is chosen in the app from three fixed rates, as with a node. The server's estimate is not used.

## What is known less well than through a node

- The history is summed up by the server, each line against the addresses of one request. It is exact while
  the coins a payment spends sit in one part of the wallet's addresses (fifty keys of each kind). When they
  sit in several parts, the line lacks its fee. When a part got back at least as much as it put in, the line
  cannot be told and is left out, and the History page says so. The balance and the coins are complete in
  every case.
- Whether a transaction was a block reward is known only while its coin is unspent.
- The server says whether it knows a transaction, not whether that is in a block. A payment that the server
  knows and the history does not list is taken to wait for a block.
- The server does not say which transaction spent a coin. A payment that did not go through and whose coin
  was then spent in another way, from another phone with the same words, stays on the Overview until its
  owner forgets it, and no payment is made meanwhile.
- There is no hash rate of the network and no time between blocks.
- The server does not say which network it works for.

The limit of a hundred addresses to a request and four smaller things (a refusal at the hand-over looks like
a node that is away; the status of a transaction does not tell a block from waiting; nothing says which
network a server works for; a line of the history does not say that it was a block reward) were reported to
the author of Northbound as issues 7 and 8 of his repository.

## How it was checked

- The independent reviewer looked at 0.6.0 three times (`independent-review-0.6.0`): at the version as it
  was first written, where it found the two high findings; at the fixes; and at what its second look had left
  open. After the third look nothing of high or medium severity was open. The last changes, two notes from
  that look and three things the emulators showed, were made after it and the reviewer has not seen them.
- The tests: 187, none failed, none skipped. Lint: 0 errors, 6 warnings, 2 hints. Both on the sources of the
  package (commit 94b8725). The tests about a server run against a stand-in for a Northbound server on a real
  regtest node.
- Deliberate faults, one at a time, each to be caught by a test: 18 in the client for a server, 41 in the
  look through a server, 21 in paying through one, 1 for the connection, 21 in the setup, the common face,
  the model and the notifier, 21 in the screens and the number of words, 24 in what the sixth look changed,
  22 in what its second look changed, 2 for its third and 1 for the pages that begin again. Each was caught.
  Where one was not caught at first, a test was made to look further before the count was taken. The earlier
  groups ran on the code as it was then, and some of their faults aim at lines that later fixes changed.
- The signed package (SHA-256 `7c0968934f4b08748d30cd1c93f76aade95155e84a7be38a996ebe97925f56c4`, version
  code 11) went through `static-checks.sh`; the output is in `evidence-0.6.0`. Permissions, components and
  libraries are those of 0.5.1. The code holds one address of a server, the one the first screen offers.
- The same package ran on emulators with Android 12, 14 and 16, seven runs on each, and all passed at the
  first try. Among the steps are a wallet server from the first screen to a payment in a block, the upgrade
  from 0.5.1 with the wallet moved from its node to a server, and a server that says no to a payment and keeps
  it. `emulator/README.md` says what was run, and why the package was built twice.
- The stand-in was compared with the public Northbound server by reading (`standin/README.md`). From the
  emulators the public server was asked for the height of the chain and nothing else.
- Not checked: a real phone, and a payment through the real server. The race between the clock and the
  reader of an answer in `ServerClient.once` has no test of its own. Android's own classes for a connection
  were not looked at when a connection is closed from another thread.
