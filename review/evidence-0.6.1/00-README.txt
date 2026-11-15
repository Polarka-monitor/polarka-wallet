Evidence for Polarka Wallet 0.6.1 (15 November 2026)

Sources: commit 96d78d5 (the commit after it adds three tests and changes documents and review material only).
Package: SHA-256 7da80705ad9c70ffc1ee4646d5f5d0bac86a707593476ac5214b1c3a420c914b, version code 12,
signed with the certificate CN=Severka, SHA-256 f441275239c880d0855a40525ca2449a2aa4992b4d4c2e63143d36c088ce103b.

01 to 05   written by review/static-checks.sh from the sources and from the package. Permissions, components,
           libraries and the one address of a server in the code are the same as for 0.6.0 (files 01, 03, 04
           and 05 are identical to those in ../evidence-0.6.0; 02 differs in the checksum and the version only).
deliberate-faults.txt
           23 faults that were put into the code that 0.6.1 changed, one at a time, each with the test that
           caught it. They ran on the sources of the package together with the three tests that the next
           commit adds, as the first line says.
server-*   what was read from the public Northbound server on 15 November 2026. Reading only; the addresses
           are public chain data and are not named in these files.
           -pages     the stand-in and the server were asked the same 51 questions about pages of coins and of
                      history and about what must be refused (review/standin/compare_pages.py)
           -sums      for seven addresses that block rewards went to, the lines of the whole history add up
                      to what the coins hold (review/standin/check_sums.py)
           -lines     four payments and one block reward: the server's line beside what the data of a
                      TrueNorth Core node give for the same transaction
           -client    the client and the engine of 0.6.1 reading the server. One of its lines is about an
                      address whose history is longer than was read, so those lines cannot add up to its
                      coins; that question was settled with -sums.
emulator-* the signed package on emulators with Android 12 (a12), 14 (a14) and 16 (a16):
           -changed   the signed package of 0.6.0 with a wallet server that answers in the new shapes: it
                      says that it cannot read the answer and shows no coins. Then 0.6.1 over it: the same
                      wallet with its coins and history, the change of a waiting payment as on its way, and
                      coins that come three to a page
           -own, -public, -server, -lock, -notgone, -saysno
                      the runs of 0.6.0, with the stand-in answering in the new shapes
           -upgrade   installed over the signed package of 0.5.1: the wallet stays, its paper is found right,
                      and the wallet is then moved from its node to a wallet server
           -realserver
                      the app as installed asks the public Northbound server for the height of the chain,
                      with no wallet in the phone
           emulator-a14-realwatch.log
                      the app as installed, given the public server and one watched address that is public
                      chain data: what it shows is the sum of the coins that the server lists for that
                      address. There is no wallet in the phone, and nothing was sent
           (review/emulator says how to repeat the runs)

In the logs the paths of the machine are cut down to the names of the files, and an address of the main
network would be written as "north1...".

Tests: 191 on the sources of the package and 194 with the three tests of the commit after it, none failed,
none skipped; lint 0 errors, 6 warnings, 2 hints (two full runs).
