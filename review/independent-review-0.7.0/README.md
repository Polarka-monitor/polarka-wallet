# The eighth look of an independent reviewer (0.7.0)

`REVIEW-0.7.0.md` in this folder is the report of a look at what 0.7.0 changed, made on 23 November 2026
between 09:04 and 09:55 CET, before 0.7 was published. It covers the two commits after v0.6.1 (43afe35 and
6dc0f38) and the three signed packages (0.6.1, and 0.7.0 in both editions), read beside walletd and faucet of
polarka-agent at 505a3de, the server and the faucet that 0.7.0 asks. The report is as the reviewer wrote it.
The reviewer is an AI model that had not seen the code being written. It began afresh, with this repository
and the earlier reports in it and a brief of questions: whether the fixed address touches what the safeguards
of 0.6.1 rest on, whether the public package is what the commit says, whether a server, the faucet or the
network can make the wallet show coins that did not arrive or pay twice, and whether it is clear whose each
server is. This is not a review by an independent human expert.

The reviewer found nothing that weakens the safeguards of 0.6.1, the one against paying twice included, and
nothing through which a server, the faucet or the network can make the wallet show coins that did not arrive.
It checked the public package in the package itself, not only in the source. It would not have held a release
back for a safety reason, but for the texts, which is a matter of hours. Findings: 0 critical, 0 high,
0 medium, 5 low, 5 notes.

## What was done

Every finding was dealt with before 0.7.1; `../changes-0.7.md` says how, where, and with which tests. In
short: the faucet's questions have a deadline as a whole and can be stopped, and its work is bounded in time
(L1); the first screen says whose a server is only for exactly the two it offers (L2); the texts of the fixed
address say what it gives away and where it is switched off, and switching it off does not show it again as a
new address (L3); the README and the guides describe 0.7.1 (L4); each edition has screen tests of its own, and
the faucet's tests stand on answers that faucet.py itself worked out (L5). The faucet's code moved to the
personal edition's own source set, so the public package holds none of it (N2), and `static-checks.sh` tells
which edition a package is before it is published (N1). What N5 says of walletd is for polarka-agent.

No reviewer has looked at 0.7.1.

## The files

- `REVIEW-0.7.0.md`: the report.
- `ReviewerFaucetTest.kt`: the reviewer's own tests of the faucet's side in 0.7.0 (a faucet that answers a
  byte a second, a cancelled coroutine, the rate of the work). Not part of the suite, and written against
  0.7.0: what its first two tests showed was put right in 0.7.1.
- `tools/`: the reviewer's scripts (gradle in the background, JUnit totals, entries and classes of the
  packages, walletd under the wallet's server tests). `walletd-as-northbound*.sh` call a copy of walletd that
  is not in this repository.
- `logs/`: totals of the reviewer's test runs, the walletd run, and the differences between the packages.
