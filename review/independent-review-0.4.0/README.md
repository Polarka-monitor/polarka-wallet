# Independent review of version 0.4.0 (the retest)

`RETEST.md` in this folder is the report of a second look by the same independent reviewer, made on
14 November 2026 on the sources of 0.4.0 (commit 5b490fb), after the twelve findings of its first review had
been fixed. Version 0.4.0 was never handed to anybody; 0.4.1 is 0.4.0 with what this report found put right.

As with the first report, so that nobody takes it for more: the reviewer was an AI agent that had not seen the
code being written. This is not an audit by a human expert or by a security firm. A review by an independent
human expert is still recommended, and until then the wallet is for small amounts.

## What it found, and what was done

The reviewer again found no way for another person, or for a node, to read the recovery words or to take
coins. Of its twelve earlier findings it confirmed ten as fixed and two as fixed in part, and it reported six
new ones (three medium, three low) and six notes. Its five new tests are in the suite as they were written
(`RetestModelTest.kt`, SHA-256 `80af88e457ab60ac145704a5f79b3866fcf8b184f93c1f92d1f9453b8d217434`). The four
that failed on 0.4.0 pass on 0.4.1.

| Finding | What was done in 0.4.1 | Tests |
|---|---|---|
| R-01 (medium). An error of a gateway or of a web server in front of the node was taken for a refusal; paying again paid twice. | Only the node's own decision about a transaction (its error codes -22, -25 and -26) is a refusal. Everything else leaves the outcome open: the node is asked whether it holds the transaction, the same transaction is handed over again, and the payment stays written down as "outcome not known" with its coins set aside. The gateway (polarka-node 0.1.2) now answers a lost answer to `sendrawtransaction` with "not known, send it again". | `RetestModelTest.aGatewayThatDidNotHearTheNodeIsNotTakenForARefusal`, `AfterRetestTest.onlyTheNodesOwnNoIsARefusal` |
| R-02 (medium). A payment that entered a block while the app was looking was reported as gone. | A look reads the node's best block before and after. If it changed, nothing is judged from that look, and another follows. A waiting payment is called gone only when it is in no block, the node does not hold it, nothing the node holds spends its coins, it is at least a minute old, and two looks in a row say so. | `RetestModelTest.aPaymentThatEntersABlockDuringALookIsNotReportedAsGone`, `FixesModelTest.aPaymentTheNodeNoLongerHoldsIsReported` |
| R-03 (medium). A node could move the key numbers thousands ahead and be told 10,000 addresses. | The app looks at the first 200 keys of each branch by itself, 40 with a public node, and moves its key numbers only within what it looks at. Beyond that it asks its owner ("Look further" on the Overview), and each yes doubles the range. The owner can also widen the search by hand and have the key numbers worked out again from another node (More). The proof of work behind the blocks a node shows is still not checked; the main README says so. | `RetestModelTest.aPublicNodeCannotPushTheKeyNumbersFarAhead`, `AfterRetestTest.aMuchUsedWalletIsFoundAfterTheOwnerLetsTheAppLookFurther`, `AfterRetestTest.keyNumbersThatRanAheadCanBeWorkedOutAgain`, `AfterRetestScreenTest.theOwnerIsAskedBeforeTheAppLooksFurther` |
| R-04 (low). The minute away was measured with the clock anybody can set. | It is measured with the clock that runs since the phone was started. A clock that seems to have run backwards locks the wallet. | `RetestModelTest.settingTheClockBackDoesNotKeepTheWalletOpen` |
| R-05 (low). A look made for one wallet could be settled into another. | A look whose wallet or node is no longer the current one is thrown away and made again. | `AfterRetestTest.aLookMadeForOneWalletIsNotSettledIntoAnother` |
| R-06 (low). Which kind of node it is follows from whether the setup has a password. | "Check before saving" says that a node with an account is treated as one's own and is given the key that shows the whole wallet. The form of the setup file is unchanged. | `AfterRetestScreenTest.checkBeforeSavingSaysWhatTheNodeWillBeGiven` |
| N-1. A block described in more than 16 MB cannot be read. | Open. Not reachable with today's blocks; named under Limits in the main README. | |
| N-2. The node is asked about at most 100 chosen coins. | Open; named under Limits in the main README. | |
| N-3. The change key number was not taken back. | A refused payment gives its change key back, and the change key never moves further than twenty past the last one the chain shows as used. | `AfterRetestTest.aRefusedPaymentLeavesNothingBehindAndTheChangeKeyStaysWhereARestoreLooks` |
| N-4. More than 200 waiting payments could be saved but not read back. | A payment beyond the 200th waiting one is not started. | `AfterRetestTest.noMoreWaitingPaymentsAreStartedThanTheWalletFileHolds` |
| N-5. A test that could not run counted as passed. | Tests that need the node programs or the gateway are reported as skipped when those are missing. | |
| N-6. The release settings are opened by the name of the task. | Unchanged; harmless, as the reviewer says. | |

The reviewer also met a test of its own that timed out in two of six runs
(`AuditScreenTest.theLockEngagesAlsoWhenThePhoneRebuildsTheScreenOnReturn`) and could not find the cause. It is
in the test: its first wait watched the model and did nothing to make the screen follow it. Two things then
made it time out before it began.

- When the stores were read only after the activity had been set up, nothing in the wait ran the main thread's
  queue. With the reading slowed down on purpose the test failed in just that way, and passed once the wait
  ran the queue.
- Under Robolectric the part of Compose that notices changes made outside it can stop working for the rest of
  a test run: its wake-up is thrown away when the main thread's queue is cleared between two tests. With a
  screen test class and `AuditModelTest` run before it in the same Java process, the test failed in each of
  four runs, always with the model loaded and locked, the owner never asked, and the lock screen appearing as
  soon as the test synchronized once.

The wait now synchronizes with the screen (`waitForIdle`), and with that the same runs pass. The change is
marked in the test. The app was not changed.

Of the weak points the reviewer named in the gateway, two more were dealt with in polarka-node 0.1.2: one IPv6
/64 counts as one address in the budgets, and a request nested too deeply is refused as a bad request.

## What is still open

- The proof of work behind what a node shows is not checked (R-03 in full). A node that lies can still show a
  false balance or history, and it can lead its user to say yes to looking further.
- N-1, N-2 and N-6 in the table.
- "It did not go through" rests on one node. A payment that this node has let go of can still be held by other
  nodes and arrive later; a new payment made with other coins would then be a second one. The notice sends
  the owner to the History before paying again. The lasting answer, building the new payment on a coin of the
  old one, is not in 0.4.1.
- The reviewer checked nothing on a phone or an emulator. The emulator runs are described in `../emulator`.
- The fixes in this table have not been reviewed. The reviewer began a third look at 0.4.1 on the same day:
  it ran the whole suite in a clone of its own (93 tests, none failed, none skipped) and had started on a
  test with a node that is stopped and started again when its work was cut short. It gave no report.

## Files

- `RETEST.md`: the report, unchanged.
- `tools/gateway_probe.py`: the reviewer's probe of the gateway's rules (86 shapes that must be refused, 14
  that the wallet uses). `tools/summ.sh`, `tools/fails.sh`: its test summaries. They expect the reviewer's own
  folder layout.
