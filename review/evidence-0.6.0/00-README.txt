Evidence for Polarka Wallet 0.6.0 (15 November 2026)

Sources: commit 94b8725 (the commit after it changes documents and review material only).
Package: SHA-256 7c0968934f4b08748d30cd1c93f76aade95155e84a7be38a996ebe97925f56c4, version code 11,
signed with the certificate CN=Severka, SHA-256 f441275239c880d0855a40525ca2449a2aa4992b4d4c2e63143d36c088ce103b.

01 to 05   written by review/static-checks.sh from the sources and from the package. Permissions, components
           and libraries are the same as for 0.5.1 (files 03 and 04 are identical to those in
           ../evidence-0.5.1; 02 differs in the checksum and the version only). 05 has one line more: the
           address of the wallet server that the first screen offers.
emulator-* the signed package on emulators with Android 12 (a12), 14 (a14) and 16 (a16):
           -own, -public   the whole way with a node of one's own and through the gateway (0.1.2)
           -server         the whole way with a wallet server (the stand-in of review/standin behind TLS),
                           with wallets of twelve words
           -lock           the lock, also the one in the top bar
           -notgone        a payment that did not go through cannot arrive beside the next one
           -upgrade        installed over the signed package of 0.5.1: the wallet stays, its paper is found
                           right, and the wallet is then moved from its node to a wallet server
           -saysno         a wallet server that says no to a payment and keeps it, and then hides its coin
           -realserver     the app as installed asks the public Northbound server for the height of the
                           chain, with no wallet in the phone; nothing else was asked of the real server
           All twenty-one runs of the first seven kinds passed at the first try.
           emulator-a16-saysno.before-the-fixes.log
                           the run -saysno on a debug build of the sources before the fixes (893f6b7): it
                           stops at its second step, where the screen says "Nothing was sent"
           *.first-build.log
                           three runs with the first package of 0.6.0 (built from 529f5ba, never handed
                           out); they stopped at three things in the app that 94b8725 put right
           (review/emulator says how to repeat the runs)

In the logs the paths of the machine are cut down to the names of the files.

Tests: 187, none failed, none skipped; lint 0 errors, 6 warnings, 2 hints (full run on the sources of the package).
