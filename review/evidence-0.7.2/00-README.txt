Evidence for Polarka Wallet 0.7.2 (23 November 2026)

Sources: commit d94fbcb; the commits after it change only texts (README.md and review/).
Package: SHA-256 ac27e19bd64c9fdeeaa707975dd2d7fe3b53cde77eca353a5cd113e9779aa9b9 (the public edition, the one
that is published), version code 15, signed with the certificate CN=Severka, SHA-256
f441275239c880d0855a40525ca2449a2aa4992b4d4c2e63143d36c088ce103b. The personal edition of the same commit, with
the faucet, has SHA-256 99b09a88320599bf9428991c32e0b105e22037c8d6c13ccf58ffde626a8bddb6 and is not published.

This is the second package of 0.7.2. The first was built from commit 482911d (public SHA-256
be54f0d35c0679b1468fe0e790685a42ec2a7278eba143023be91ba00d4d46db); its texts still advised small amounts only.
It went through the runs that ../emulator/README.md describes; its personal edition was sent to the owner's
phone for a try, and nothing of it is published.

01 to 06   written by review/static-checks.sh from the sources and from the public package (06: which edition
           the package is, read from its code: the public one, without the faucet).
emulator-a16-*
           the package of the release on an emulator with Android 16, on 23 November 2026:
           -own, -public, -server
                      a wallet made, paid to, paying, its paper checked, removed and brought back, with a node of
                      one's own, through the gateway and with a wallet server
           -lock      the lock of the app and the fingerprint
           -notgone   a payment that did not go through is not forgotten
           -upgrade   installed over the signed package of 0.7.1: the wallet stays and works
           -saysno    a wallet server that says no and keeps what it was shown
           (review/emulator says how to repeat the runs)

In the logs no path of the machine and no address of the main network occurs.

Tests: MacBook, 23 November 2026, 20:32 to 20:56, commit d94fbcb: core 39, node 34 (against a TrueNorth
Core node on regtest), app 151 with the public edition and 159 with the personal one; none failed, none
skipped. Lint: 0 errors, 6 warnings, 2 hints for both editions. In a clean Linux container, with dependency
verification strict: the tests of core, the unit tests and lint of the public edition, the debug build and
the compiling of both editions.
