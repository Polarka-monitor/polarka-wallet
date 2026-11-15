# The sixth look of the independent reviewer (0.6.0)

`REVIEW-0.6.0.md` in this folder is the report of the sixth look by the same independent reviewer, made on
14 November 2026, with two sections that the reviewer added after looking at the fixes (14 and 15 November).
It covers what 0.6 added to 0.5.1: the eight commits from 86d735e to 893f6b7, which let the wallet work
through a wallet server and give the choice of 12 or 24 words; then the fixes, commits d739709 and c726e4d.
The report is as the reviewer wrote it, with one exception: its first lines named a product, this repository
names none, and the reviewer reworded them at the author's request. The reviewer is an AI model that had not
seen the code being written; this is not a review by an independent human expert.

The reviewer found nothing wrong in how the client reads a server's answers, found the history that is put
together from parts free of untrue lines, and found the setup and the twelve words sound. It also found that
the safeguard against paying the same thing twice did not hold through a wallet server: two findings of
high severity, one of medium, four low ones and five notes. Its advice was not to hand out 0.6 with somebody
else's server filled in until the two high findings were dealt with. No package of 0.6 had been built at
that point, and nothing of it had been handed to anybody.

## What the two high findings come to

Whoever is shown a signed transaction can keep it and send it later, whatever they answered. Up to 0.5.1 the
wallet showed a transaction to nobody but a node, and it forgot a payment that the node refused. That is
right for a node of one's own. It is not right for a wallet server, which is somebody else's and is handed
every payment; and, as the reviewer pointed out, it had never been right for a public node either.

Since the fixes one rule holds for everybody who is not the owner's own node: **a payment that has been shown
to them is not forgotten on their word.**

## What was done

| Finding | What was done | Tests |
|---|---|---|
| H1 (high). A check before sending that fails or says no made the app forget the payment, although the server had the whole signed transaction by then. A server that kept it, hid its coin and passed both payments on made the wallet pay twice. | A payment that a wallet server refuses is kept as one that did not go through: it stays on the Overview, and the next payment is built on one of its coins, so that only one of the two can reach a block. A check that cannot be made (no answer, a busy server) decides nothing: the hand-over is tried all the same, and what cannot be found out is "outcome not known". The message no longer says "Nothing was sent". `ServerEngine.deliver`, `Gateway.ownersNode`, `WalletModel.sendNow`, `Problem.Kind.REFUSED_KEPT`. | `SixthLookTest.aPaymentThatAServerRefusedIsNotForgotten`; `ServerPayTest.aPaymentThatTheNodeBehindTheServerWillNotTake`; `ServerModelTest.aRefusalAndAHandOverWhoseOutcomeNobodyTells`; `SixthLookScreenTest` |
| H1, the node's side (outside the eight commits, named by the reviewer). A public node was shown the signed transaction when it was signed, before the payment was written down, and its no left no trace. | A public node sees the transaction only in the hand-over, after the payment is in the wallet's file, and a payment it refuses is kept in the same way. A node of one's own is asked as before, first, and what it refuses is forgotten. `Engine.sign`, `Engine.deliver`, `Engine.ownersNode`. | `SixthLookTest.aPublicNodeSeesAPaymentOnlyWhenItIsWrittenDownAndItsNoIsNotForgotten`, `theOwnersNodeIsAskedBeforeAnythingIsWrittenDownAndItsNoIsTheEnd` |
| H2 (high). A coin that the server did not list, of a payment that the server said it did not know, was taken as spent in a block. By leaving a coin out a server could make the app let go of a payment that did not go through, and the next payment no longer stood against it. | Nothing is concluded from a coin that is not listed. Through a server a payment counts as overtaken only when the server shows, in a block, another payment of this wallet that spends one of its coins; that is something a server has to say and cannot merely leave out, and the two payments cannot both reach a block. A payment whose coin went another way stays until its owner forgets it, and while it has no coin among those listed the wallet makes no payment. The Overview says so. `ServerEngine.judge` (the value `ANOTHER` is gone); `dropped_text_server`, `dropped_no_coin_server`. | `SixthLookTest.aCoinThatAServerLeavesOutDoesNotUndoTheGuard`; `ServerEngineTest.aPaymentThatDidNotGoThroughOneWhoseCoinWentElsewhereAndOneThatCameBack` |
| M1 (medium). With an honest server a payment that did not go through was let go while the payment built on its coin only waited for a block. | The same change: the coin that a waiting payment of the wallet's spends is tied up, not spent in a block. The first payment is let go when the second is in a block. The Overview does not warn of a missing coin while a payment of the wallet's stands against the entry. `ServerEngine.judge`; `OverviewTab` in `Tabs.kt`. | `SixthLookTest.aPaymentThatDidNotGoThroughIsKeptWhileTheOneBuiltOnItsCoinOnlyWaits`; `ServerEngineTest.aPaymentThatWaitsOnTheCoinOfOneThatDidNotGoThrough`; `SixthLookScreenTest` |
| L1 (low). A payment's own id in `spentBy` was written over when a second payment shared its coin. | Follows from the above: only a payment shown in a block is written there, and nothing is written over it. | `ServerEngineTest.aPaymentThatWaitsOnTheCoinOfOneThatDidNotGoThrough` |
| L2 (low). Every refusal of a question (HTTP 400) was shown as "a server of another network", also the server's refusal to list more than 10,000 coins. | That refusal is told apart by the server's words and said as what it is. `ServerEngine.asked`, `Problem.Kind.TOO_MANY_COINS`. | `SixthLookTest.moreCoinsThanAServerListsAreCalledThat` |
| L3 (low). No time limit for a question as a whole: a server that sends a byte now and then could hold a look or a hand-over without end. | Every question has a limit as a whole, ninety seconds, and less where less is asked for: the connection is closed when it is over, and an answer that is being read is given up between two reads. A hand-over gives its check twenty seconds and every further question what is left of forty, ten at least. `ServerClient.once`, `call`, `DEADLINE_MS`; `ServerEngine.deliver`. | node `ServerClientTest.anAnswerThatComesALittleAtATimeIsNotWaitedForWithoutEnd`; `ServerPayTest.aServerThatAnswersVerySlowlyDoesNotHoldTheHandOver` |
| L4 (low). Texts that promised more than the code did or left something out. | "Nothing was sent" is gone from what is said after a server's no. The first screen and the check before saving name what else a server is told (addresses that are watched or emptied through the app) and that it could call a payment done that is not. The block for a paper address says that a server is named forty addresses of the words. The note about a history that is not whole names all three reasons. The Czech texts say the same. | `SixthLookScreenTest`; `HistoryNoteTest.aHistoryThatIsNotWholeSaysSo` |

## The notes

| Note | What was done |
|---|---|
| N1. "On its way" can count an amount that another waiting transaction already spends. | Left as it is. It needs a chain of waiting transactions over the wallet's own outputs, which this app does not make (it builds on coins in blocks only), and it concerns one figure on the screen. |
| N2. A notification through a server goes by the first address paid in a part. | Left as it is: a payment from outside to a change key is not announced. |
| N3. A server that says "known" every time keeps a payment waiting for ever, and there is no way to remove a waiting payment by hand. | Left as it is. Removing a waiting payment by hand would take away the safeguard for it; the owner can connect the wallet to another server or a node, which settles it. Since the reviewer's look at the fixes the app names a payment that has waited for over an hour (S1 below). |
| N4. Coins and history of one look can come from two states of the waiting transactions. | Left as it is: the next look puts the figure right, and nothing is judged from it. |
| N5. A setup refuses plain http also to a private address, which the client would take. | Left as it is, on purpose: a setup is https only. |

## The two looks at the fixes

The reviewer looked at the fixes (d739709) on 14 November and found the ways to a double payment closed, with
nothing new of high or medium severity. On 15 November it looked at what was done about the rest (c726e4d):
every item closed, and two notes. Its sections "After the fixes (d739709)" and "After the second look
(c726e4d)" stand at the end of the report.

| What the look at the fixes left open | What was done | Tests |
|---|---|---|
| L3, the rest. The time limit of a question did not cover the wait for its turn: one question at a time goes to a server, and behind a slow one a check that was given a second waited seven. | The turn is taken in the order of asking and within the question's time. A question that does not get its turn in time fails as not answered in time and is not sent. `ServerClient.call`. | `ServerClientTest.aQuestionDoesNotWaitForItsTurnLongerThanItsTime` |
| S1 (low). A lying server can still lead the owner to a second payment, through the owner's own hand: (a) by hiding the coin of a payment that did not go through until the owner forgets the entry; (b) by calling a payment known for ever, or by turning one that did not go through back into a waiting one, until the owner pays again beside it; (c) by calling a payment done. | (a) The entry says to look through another server or a node before it is forgotten; there is one test more, for a coin that stays hidden. (b) A payment that has waited for a block for over an hour is named on the Overview and where the next payment is confirmed, with the advice to find out what became of it and to look through another server or node. There is no action "pay again in its place". (c) The first screen has said so since the first fixes. | `SixthLookScreenTest.aCoinThatStaysHiddenStopsEveryPaymentUntilItsOwnerForgetsTheEntry`, `aPaymentThatHasWaitedLongIsNamedBeforeTheSameThingIsPaidAgain`; `AfterSixthLookPlainTest` |
| S2 (low). Refusals fill the list of payments that did not go through. With the list full a refusal stayed "outcome not known", and the text promised that a refresh would settle it. | The same transaction refused again is kept once. With the list full the screens say what is true (`REFUSED_NO_ROOM`, `NO_ROOM`, the entry of the open payment). Refusals that only share their coins are not merged: each kept transaction could arrive and pays what it says. | `SixthLookScreenTest.theSameTransactionRefusedTwiceIsKeptOnce`, `withNoRoomLeftARefusedPaymentIsNotPromisedWhatALookCannotDo` |
| S3 (note). The clock that ends a question could still close a connection whose answer had been read. | A question is ended once, by its reader or by the clock, and the clock closes the connection only when it ended the question. `ServerClient.once`. | None of the race itself. The two deliberate faults in the clock and in the reader are caught as before. |
| S4 (note). A refused emptying of a paper address was shown and not kept. | Nothing of a paper address is stored, so nothing can be kept. After a no from a server or a public node the screen says that the transaction was seen and could still be passed on, and what to do. "Nothing was sent" is said only when the owner's own node refused. | `AfterSixthLookPlainTest`; `SixthLookScreenTest.aRefusedEmptyingOfAPaperAddressIsNotCalledNothing` |
| From the third look (note). With the list full the text said that an open payment cannot be settled. A look that finds it waiting does settle it. | The two texts name both cases. | `SixthLookScreenTest.withNoRoomLeftARefusedPaymentIsNotPromisedWhatALookCannotDo` |
| From the third look (note). A payment that came back from those that did not go through was given a new time, so a server that moved it to and fro could keep its hour from ever passing. | A payment keeps the time at which it was made, among those that did not go through and when it comes back. `WalletModel.settle`. | `SixthLookScreenTest.aPaymentThatComesBackFromThoseThatDidNotGoThroughKeepsItsTime` |

The third look was asked to be short. The reviewer read the diff of c726e4d, ran the tests of the client and
its own test of the turn, and did not run the whole suite or read the new tests of the screens. What the last
two rows changed was done after that look, and the reviewer has not seen it.

## The reviewer's own tests

`ReviewerServerTest.kt` in this folder holds the reviewer's five tests as they stood after its last look.
Four are those of the sixth look. Each of them asserts a fault, so that it passed while the fault was there,
and fails at that assertion since the fixes; the reviewer changed one thing in them, the removed constant
`ServerEngine.ANOTHER` to its former text, so that the file still compiles. The fifth showed that a question
waited for its turn longer than its time, and fails since c726e4d. They are not part of the suite.
`SixthLookTest` tells the same stories as the first four and asserts the opposite: the shop is paid once. `reviewer-mutants.sh` and
`reviewer-mutants.log` are the five faults the reviewer planted in the sources and the result (all caught by
the suite as it was).

SHA-256 of the reviewer's files as they were handed over, the first two after the third look:

    2f533203b53ef68aeb29ad94a39d5c67b679f3b82b5be12b9e4704446428a203  REVIEW-0.6.0.md
    f65c5bcbc3cc87ec38b200e9f116a469afee4fe36fdc92f56ccfb1dea863f65b  ReviewerServerTest.kt
    e9187ff314e05ad3df1a8715da4903e6a6a1cfd54a382179be23903851589d2b  reviewer-mutants.sh
    57f9fc1df8fd00ad169013e74fd91e7c0875feaa67fa645a09e5fff2bb26ea5b  reviewer-mutants.log
