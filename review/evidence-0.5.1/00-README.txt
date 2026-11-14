Evidence for Polarka Wallet 0.5.1 (14 November 2026)

Sources: commit 6ec7a4c (the commits after it change documents and review material only).
Package: SHA-256 1dbb549c1b801f0d6867e958671c18683a9c50b2941dc972c17b101ecc88c4ce, version code 10,
signed with the certificate CN=Severka, SHA-256 f441275239c880d0855a40525ca2449a2aa4992b4d4c2e63143d36c088ce103b.

01 to 05   written by review/static-checks.sh from the sources and from the package. Permissions, components
           and libraries are the same as for 0.5.0 (files 03 and 04 are identical to those in
           ../evidence-0.5.0; 02 differs in the checksum and the version only). 05 has one line less: the
           code no longer holds the address of a node.
emulator-* the signed package on emulators with Android 12 (a12), 14 (a14) and 16 (a16):
           -own, -public   the whole way with a node of one's own and through the gateway (0.1.2), with the
                           check of the whole paper and a wallet brought back after a slip in a word
           -lock           the lock, also the one in the top bar
           -notgone        a payment that did not go through cannot arrive beside the next one
           -upgrade        installed over the signed package of 0.5.0, the wallet stays and its paper is
                           found right
           *.first-try.log three runs that stopped on their first try for reasons in the steps or in the
                           emulator, not in the app; each was repeated and passed
           (review/emulator says how to repeat the runs, and what the three first tries were)

Tests: 128, none failed, none skipped; lint 0 errors, 11 warnings, 2 hints (full run on the sources of the package).
