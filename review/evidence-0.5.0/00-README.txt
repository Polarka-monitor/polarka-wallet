Evidence for Polarka Wallet 0.5.0 (14 November 2026)

Sources: commit 5d09ca2 (the commits after it change documents and review material only).
Package: SHA-256 a571e5014cab223dc68ace3fbca884cf8a52a53c7f227614aa05bbfce8713bca, version code 9,
signed with the certificate CN=Severka, SHA-256 f441275239c880d0855a40525ca2449a2aa4992b4d4c2e63143d36c088ce103b.

01 to 05   written by review/static-checks.sh from the sources and from the package. Permissions, components,
           libraries and the internet hosts in the code are the same as for 0.4.1 (files 03, 04 and 05 are
           identical to those in ../evidence-0.4.1; 02 differs in the checksum and the version only).
emulator-* the signed package on emulators with Android 12 (a12), 14 (a14) and 16 (a16):
           -own, -public   the whole way with a node of one's own and with the public node (gateway 0.1.2)
           -lock           the lock, also the one in the top bar
           -notgone        a payment that did not go through cannot arrive beside the next one
           -upgrade        installed over the signed package of 0.4.1, the wallet stays
           (review/emulator says how to repeat it, and what had to be put right on the Android 12 emulator)

Tests: 116, none failed, none skipped; lint 0 errors, 11 warnings, 2 hints (full run on the sources of the package).
