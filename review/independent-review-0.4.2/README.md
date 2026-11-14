# The third look of the independent reviewer (0.4.1 and 0.4.2)

`RETEST2.md` in this folder is the report of a third look by the same independent reviewer, made on
14 November 2026. It covers the fixes that followed the second look (version 0.4.1, commit 9499ff8), the
safeguard added in 0.4.2 (commit ddbcd76) and the gateway of polarka-node 0.1.2. The report is unchanged. The
reviewer is an AI model that had not seen the code being written; this is not a review by an independent human
expert.

The reviewer found nothing of medium or high severity, three low findings and seven notes. Neither 0.4.1 nor
0.4.2 was ever handed to anybody. What the report names was dealt with in 0.5.0, as the table says. 0.5.0 also
has a new look for the screens; no decision about coins changed with the look.

## What was done

| Finding | What was done in 0.5.0 | Tests |
|---|---|---|
| R2-01 a public node can lead the app to an address that a wallet brought back from its words does not look at | Every look covers all the keys the app may look at by itself (40 with a public node, 200 with a node of one's own), and so does a wallet brought back from its words (`Engine.windowOf`). A public node is therefore always named 2 x 40 addresses. | the reviewer's `Retest2ModelTest.coinsOnTheAddressTheAppShowsAreFoundWhenTheWalletIsBroughtBack`, which failed, passes |
| R2-02 "wait for the next block" can stay for ever, and the way out is not named | The message no longer says that a block will change it, and points to the entry on the Overview. The entry says when the node shows none of the payment's coins, what that can mean, and when "Forget this payment" is right. The app still does not let such a payment go by itself (see below). | the third test of `Retest2ModelTest`, with its end changed and marked; `NotGoneThroughScreenTest` |
| R2-03 behind an address that stands for several nodes, a no on a second try does not show that the first try did not go out | A no from the node counts as a refusal only when no try went before it. After a try without an answer the payment stays "outcome not known" and is settled like any other such payment (`Engine.deliver`). | `ThirdLookTest.aNoAfterATryWithoutAnAnswerSettlesNothing` |
| R2-03 a made-up block that spends a coin of a kept payment makes the app let it go | Open. It needs the work behind the blocks to be checked, as R-03 does. It concerns nodes that the project does not run. | |
| N2-1 a third payment made while one is "outcome not known" stands against nothing | While the outcome of a payment is not known, no other payment is worked out or sent (`WalletModel.prepare`, `confirmSend`). A look at the node settles the open one about a minute after it was handed over. | `NotGoneThroughTest` (in its helper `lostPayment`) |
| N2-2 one notice hides another | Open. The Overview and the History show both payments. | |
| N2-3 the owner's yes to look further goes along to another node | Setting up another node puts it back to what the app looks at by itself. Keys in use are looked at in any case (`WalletModel.acceptOffer`). | `ThirdLookTest.theOwnersYesToLookFurtherDoesNotGoAlongToAnotherNode` |
| N2-4 removing the wallet drops what the phone remembers about payments, and the dialog speaks of the words only | The dialog says so when there are waiting or kept payments. | `NotGoneThroughScreenTest` |
| N2-5 handing a payment over can last about 90 s, not 40 | The comment at `Engine.deliver` says what is true: at most three tries, none started later than forty seconds after the first, and one try can itself wait for a busy node. | |
| N2-6 working the address numbers out again with a node that lags sets them back | Open. Since every look covers 40 or 200 keys, the numbers cannot fall back out of what the app looks at by itself. | |
| N2-7 the number of lint warnings differs between the two counts | Both counts have 0 errors. | |

The reviewer's test file is in the suite as `Retest2ModelTest.kt` (SHA-256 of the reviewer's own file:
`05e285ca0231c02efaa0506c2f4051f9f6a8b4a44ef835b12754ea631d54c568`). Its first two tests are unchanged. The end
of the third was changed, and the change is marked in the file: the reviewer expected the payment to go through
after the next block; the app keeps refusing until the owner lets the kept payment go, and the test now checks
that, the texts, and the way out.

### Why a kept payment is not let go by itself

A payment that did not go through is let go when a block shows it after all, or shows one of its coins spent
by another transaction. That is evidence. That a node shows none of its coins is not: the node may lag behind
the network, the coin may lie on an address the app no longer looks at, or the node may not show the truth.
Letting the payment go on that ground would open again what 0.4.2 closed. So the app says what it sees and
leaves the decision to the owner.

### Tests that were changed on this occasion

- `Retest2ModelTest.kt`: the end of the third test, as said above.
- `AuditSupport.kt` (the reviewer's helper from the first review): `held()` asks the node again when it
  answers "Scan already in progress". The node scans for one caller at a time, and the app under test looks at
  the node by itself after a payment; the test failed once on that. Marked in the file.
- `ScreenTest`: after a block it waits for the look that follows a look made while the block arrived.
- `RegtestWalletTest`, `WalletScanTest`: they expected a block filter to name exactly the blocks of the wallet.
  A filter can, rarely, name one more; one run in about a hundred failed on that.

## What is still open

- The proof of work behind what a node shows is not checked (R-03 in full, R2-03). A node that lies can show
  a false balance or history, lead its user to say yes to looking further, and undo the safeguard of 0.4.2 for
  one payment.
- N-1, N-2 and N-6 of the second look, N2-2 and N2-6 of this one.
- The reviewer checked nothing on a phone or an emulator. The emulator runs are described in `../emulator`.
- What 0.5.0 changed after this report (the table above, and the new look) is said under "After the report".

## Files

- `RETEST2.md`: the report, unchanged.
