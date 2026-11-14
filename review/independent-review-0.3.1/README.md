# Independent review of version 0.3.1

`AUDIT.md` in this folder is the report of an independent review of version 0.3.1, made on 14 November 2026.

What it is, so that nobody takes it for more: the reviewer was an AI agent that had not seen the code being
written and was not given the author's own review. It read every source file, re-ran the tests, wrote 24 tests
of its own against a private regtest node, and inspected the signed package. The reviewer calls its work an
audit, and its text is kept here as it was written. It is not an audit by a human expert or by a security
firm. The wallet has been checked twice (by its author and by this reviewer); a review by an independent human
expert is still recommended, and until then the wallet is for small amounts.

## What came of it

The reviewer found no way for another person, or for the node, to read the recovery words or to take coins.
It did find 12 defects (2 high, 4 medium, 6 low) and wrote a test for each that failed on 0.3.1. All twelve are
fixed in 0.4, and so are the notes N-1 to N-7, N-9 and N-10; N-8 (how the dependencies are pinned) is described
in the main README. The reviewer's tests are part of the test suite now (`Audit*Test.kt` in the three modules)
and pass.

Five of the reviewer's tests had to be adapted to the fixed behaviour. Each change is marked in the test with
the words "Adapted after the fix" and says why:

| Test | Change |
|---|---|
| `AuditNodeTest.amountsAreReadExactly` | F-09 is fixed by refusing amounts that cannot exist; the test expected the largest 64-bit number to be taken. |
| `AuditPlainTest.theIdOfAPaymentIsTheIdOfWhatWasSigned` | The app now asks the node whether a waiting transaction spends the chosen coin; the test's stand-in node answers that call too. |
| `AuditModelTest.aLostAnswerDoesNotEndInPayingTwice` | The test paid a second time because the app had said that nothing was sent. The fixed app says "Sent", so the second payment is made only if the app still says that nothing went out. |
| `AuditScreenTest.theLockEngagesAlsoWhenThePhoneRebuildsTheScreenOnReturn` | The moment the app left the front is kept in the model now (that is the fix); the test shifts it there instead of in the activity. |
| `AuditScreenTest.thePaperConfirmationShowsTheAddressThatWillBePaid` | An edit of a field now throws the worked-out payment away; the test has it worked out again before confirming, and checks that the confirmation shows the address that is paid. |

## Files

- `AUDIT.md`: the report, unchanged.
- `tools/apk-classes.sh`, `tools/apk-native.sh`: how the reviewer compared the signed package with the sources
  and with the pinned native library. They expect the reviewer's own folder layout.
- `tools/summ.sh`, `tools/fails.sh`: the reviewer's test summaries; `review/tests-summary.sh` is the same idea
  for this repository.
