# The wallet on emulators

These scripts run the wallet's package, unchanged, on Android emulators against a private test network, and
drive it the way a user would. They are how the claim "tried on emulators with Android 12, 14 and 16" can be
repeated. Nothing here touches a real network, a real coin or a real key.

## What is what

| File | What it does |
|---|---|
| `emu-env.sh`, `emu-create.sh`, `emu-start.sh` | the Android SDK's emulator: three virtual phones (API 31, 34, 36), started without a window |
| `lock_and_finger.py` | gives a fresh emulator a screen lock (PIN 1234) and one fingerprint, through the Settings app |
| `make_ca.sh`, `trust_ca.sh` | a throwaway certificate authority, and how a running emulator is made to trust it until its next restart |
| `tls_relay.py` | TLS in front of a local service |
| `tls_gate.py` | the same in front of the node, but it can keep a transaction to itself: what a payment looks like whose outcome nobody knows |
| `bench.py` | the test network: a regtest TrueNorth Core, the public gateway (`gateway.py` of polarka-node), a stand-in for a Northbound wallet server (`../standin`), and TLS in front of all three |
| `emu.py` | reads the screen as text and touches it, through adb |
| `flows.py` | what a user does in the wallet, as steps (the screens since 0.5.0) |
| `flows_0_4.py` | the same steps on the screens of 0.4.1; `t05_upgrade.py` needs them for the older package |
| `t01_selftest.py` | starts the app and runs its self-test |
| `t02_wallet.py` | with a node of one's own, a public node or a wallet server (`own`, `public`, `server`), the whole way: setup, new wallet, the whole paper checked against it, receive, send, the app closed while a payment waits, a paper address emptied, wallet removed and brought back after a slip in a word was named |
| `t03_lock.py` | the lock after a minute away, also when Android rebuilds the screen meanwhile; the lock in the top bar; optionally what the app says after the screen lock of the phone is removed |
| `t04_not_gone_through.py` | a payment that is kept from the node: "outcome not known" and no other payment meanwhile, then "did not go through", the next payment built on its coin, and the kept transaction refused by the node afterwards |
| `t05_upgrade.py` | the package installed over the one before it (0.5.0, or 0.4.1 with `flows_0_4.py`): the same wallet opens, still knows a waiting payment, signs with the words the older package stored, and finds the paper written with the older package right |
| `t06_real_server.py` | the first screen as it is, with the public Northbound server that it offers: the app connects and shows the height of the real chain. No wallet is made, so the server is told no address; it is asked for its tip and nothing else |
| `t07_server_says_no.py` | a wallet server that is not honest: it says no to a payment and keeps it, and later hides its coin. The payment is not forgotten, the next one is built on its coin, the node has no room for the kept transaction, and while the coin is hidden nothing is paid |
| `t08_server_changed.py` | a wallet server that changed the shape of its answers (since 0.6.1): the signed package of 0.6.0 against the stand-in as it answers now, which it cannot read, then the package under test installed over it: the same wallet with its coins and history, the change of a waiting payment, and coins that come in pages |
| `t09_real_server_watch.py` | the package as installed, given the public Northbound server and one watched address that is public chain data (since 0.6.1): what the app shows for the address is compared with the coins that the server lists for it, read page by page from the computer. No wallet is in the phone and nothing is sent |
| `probe_server.py`, `probe_says_no.py` | the bench's wallet server asked from the computer, without a phone: the first through TLS as the app asks it, the second the two switches that `t07_server_says_no.py` uses |
| `matrix.sh` | the seven runs for one emulator, one after the other |

## Why it is built this way

- The wallet forbids screenshots, so the screen is read from the accessibility tree (`uiautomator dump`).
  That is also why the scripts match English texts: the emulators run in English.
- The wallet trusts only the system's certificate authorities, as every app does by default. Instead of a
  special build that trusts a test certificate, the emulator is made to trust the test authority. The package
  under test is then the very file that would be handed out. It needs an emulator image without Google Play
  (those allow `adb root`).
- The emulator reaches the host as 10.0.2.2. The bench listens there: 18651 goes straight to the node (a node
  of one's own, user `phone`, limited like a real phone account), 18652 goes through the gateway (a public node),
  18654 is the stand-in for a wallet server.
  `BENCH=2` and `BENCH=3` start further benches on other ports, so that three emulators can run side by side.
  `t04_not_gone_through.py` puts its gate on a third port of the same bench (18653, 18673, 18683).
- Android installs a package over another one only when both are signed with the same key. `t05_upgrade.py`
  therefore needs the two signed packages; it cannot be repeated with packages built elsewhere.

## A run

    . ./emu-env.sh && zsh emu-create.sh            # once
    zsh emu-start.sh 34 5560                       # Android 14 on port 5560
    python3 lock_and_finger.py emulator-5560
    zsh make_ca.sh && zsh trust_ca.sh emulator-5560
    TN_BIN=/folder/with/truenorthd nohup python3 bench.py start &
    python3 t02_wallet.py emulator-5560 /path/to/polarka-wallet.apk own
    python3 t02_wallet.py emulator-5560 /path/to/polarka-wallet.apk public
    python3 t03_lock.py emulator-5560
    python3 t04_not_gone_through.py emulator-5560 /path/to/polarka-wallet.apk
    python3 t05_upgrade.py emulator-5560 /path/to/the-package-before.apk /path/to/polarka-wallet.apk
    python3 t02_wallet.py emulator-5560 /path/to/polarka-wallet.apk server
    python3 t07_server_says_no.py emulator-5560 /path/to/polarka-wallet.apk
    python3 t08_server_changed.py emulator-5560 /path/to/polarka-wallet-0.6.0.apk /path/to/polarka-wallet.apk
    python3 t06_real_server.py emulator-5560 /path/to/polarka-wallet.apk       # asks the public server for its tip
    python3 t09_real_server_watch.py emulator-5560 /path/to/polarka-wallet.apk /path/to/a-file-with-one-address   # reads the public server

`t06_real_server.py` and `t09_real_server_watch.py` are the two runs that touch a real network. They only
read, and they are not to be repeated often.

`bench.py` needs `TN_BIN` (the folder with the official `truenorthd` and `truenorth-cli`) and finds the gateway
through `POLARKA_GATEWAY`. The other paths inside the scripts assume the folder layout of the machine they were
written on (`~/north-build`); change `emu-env.sh`, `emu-start.sh`, `make_ca.sh`, `trust_ca.sh`, `matrix.sh` and
the first lines of `emu.py` for another one. An emulator that was restarted shows its lock screen and has
forgotten the test authority: unlock it and run `trust_ca.sh` again.

## What was run for 0.6.1

On 15 November 2026 the first signed package of 0.6.1 (SHA-256 `7da80705ad9c70ffc1ee4646d5f5d0bac86a707593476ac5214b1c3a420c914b`,
built from commit 96d78d5) went through eight runs on Android 12, 14 and 16: the seven of 0.6.0, with
`t05_upgrade.py` again over the signed package of 0.5.1, and `t08_server_changed.py`. Every run ended with its
line of success: `ALL 12 STEPS PASSED` with a node of one's own and through the gateway, `LOCK STEPS PASSED: 4`,
`NOT GONE THROUGH: ALL 6 STEPS PASSED`, `UPGRADE: ALL 5 STEPS PASSED (0.5.1 -> 0.6.1)`, `ALL 18 STEPS PASSED`
with a wallet server, `A SERVER THAT SAYS NO: ALL 6 STEPS PASSED` and `A SERVER THAT CHANGED ITS ANSWERS: ALL 6
STEPS PASSED (0.6.0 -> 0.6.1)`. None of the twenty-four had to be repeated. The logs are in `../evidence-0.6.1`.

The wallet server of these runs is the stand-in of `../standin`, which now answers in the shapes that the
public server has had since 15 November 2026: a line of history is one signed amount with the fee of the
transaction, and coins come a page at a time. The steps of the seven runs are those of 0.6.0 and did not have
to be changed. In the run with a wallet server the wallet again asked the server 69 times over 2 connections.

`t08_server_changed.py` is the run for what happened on that day. The signed package of 0.6.0, as it was
handed out, is connected to the stand-in. A new wallet that has no history is shown. After 25 NORTH were paid
to it and were in a block, the Overview says "The server answered with an error: the server sent an answer in
an unknown form (received_amount)", and none of the coins are shown: 0.6.0 cannot read the server, and it
does not show a part of what it could not read. Then 0.6.1 is installed over it. The same wallet opens after
the fingerprint, with its 25 NORTH and the payment in its history. A payment of 5 NORTH made with 0.6.1
waits for a block: its coin is gone from the server's list, and its change of 19.99999674 NORTH shows as on
its way. After the block the change can be spent and nothing is on its way. Last, the stand-in is told to
list three coins to a page, four more payments arrive, and the wallet's five coins are read from two pages.

Two runs touched the public Northbound server, both reading only. `t06_real_server.py` ran once on each
emulator: the app, as installed and with the address it offers, connected to the server and showed the height
of the main chain (blocks 25235, 25237 and 25238, each the height that the computer was told at the same
moment). There was no wallet in the phone, so the server was named no address. `t09_real_server_watch.py` ran
once, on Android 14: the app was given a setup file that names the public server and one address to watch, an
address that is public chain data and holds several thousand coins. The app showed 33829.09039810 NORTH for
it. That is the sum of the 3,353 coins that the server listed for the address at
block 25240, which the computer read over four pages right before and right after the app's look, with the
same result. The History page listed block rewards of that address. Nothing was sent, and there was no wallet
in the phone.

Not run on an emulator: what is listed for 0.6.0 below; a payment through the public server; a block or a
transaction that arrives in the middle of a look; lines of history that do not fit together; and more coins
than the app reads through a server. The last three have tests in the suite, against the stand-in on a real
node.

## What was run for 0.6.0

On 15 November 2026 the signed package of 0.6.0 (SHA-256 `7c0968934f4b08748d30cd1c93f76aade95155e84a7be38a996ebe97925f56c4`,
built from commit 94b8725) went through seven runs on Android 12, 14 and 16: `t02_wallet.py` with a node of
one's own, through the gateway (0.1.2) and with a wallet server, `t03_lock.py`, `t04_not_gone_through.py`,
`t05_upgrade.py` over the signed package of 0.5.1, and `t07_server_says_no.py`. Every run ended with its line
of success: `ALL 12 STEPS PASSED` with a node of one's own and through the gateway, `LOCK STEPS PASSED: 4`,
`NOT GONE THROUGH: ALL 6 STEPS PASSED`, `UPGRADE: ALL 5 STEPS PASSED (0.5.1 -> 0.6.0)`, `ALL 18 STEPS PASSED`
with a wallet server, and `A SERVER THAT SAYS NO: ALL 6 STEPS PASSED`. None of the twenty-one had to be
repeated. The logs are in `../evidence-0.6.0`.

The wallet server of these runs is the stand-in of `../standin` on the bench's regtest node, behind TLS. The
steps that are new with it: the first screen offers the Northbound server and says whose it is and what a
server sees; another address is typed over the one that is filled in; a server that works for another network
than the wallet is named as that; a setup file names a server; the wallets have twelve words; an amount that
waits for a block is shown apart from what can be spent; a paper address is emptied through the server; a
server that asks to slow down is waited for, and one that stays behind its node is called busy; and the
mining page says what a server does not tell. In each of the three runs the wallet asked the server 69 times
over 2 connections.

`t05_upgrade.py` installs 0.6.0 over 0.5.1 with a wallet and a waiting payment in it. The new package signs
with the words the old one stored and finds the paper written with it right. Then the setup is removed and a
wallet server saved in its place: the same wallet shows the same balance and its history there, and a payment
through the server goes into a block.

`t07_server_says_no.py` is the run for what the reviewer's sixth look found. The stand-in is told to say no to
a payment and to keep it: the app keeps the payment on the Overview, builds the next one on its coin, and the
node then has no room for the kept transaction, neither beside the new payment nor after its block. Then the
stand-in keeps a second payment and leaves its coin out of its list: the entry stays and no payment is worked
out, until the coin is listed again. The same run on a build of the sources before the fixes (893f6b7, a debug
build) stops at its second step: after the server's no the screen says "Nothing was sent", which the app
cannot know of a server that was shown the whole transaction (`emulator-a16-saysno.before-the-fixes.log`).

`t06_real_server.py` ran once on each emulator after the seven runs. The app, as installed and with the
address it offers, connected to the public Northbound server and showed the height of the main chain (block
24685 on all three, the height that the computer was told at the same moment). There was no wallet in the
phone, so the server was named no address. Nothing else was asked of the real server.

The package was built twice. The first one, from 529f5ba, was put through three of the runs as a rehearsal,
and they stopped in three places. All three were in the app and none in what it does with coins: the banner
of a test build counted the checks and the count was stale; on Android 16 the keyboard stood over the first
screen, which begins with a field since 0.6; and after a setup was removed on the More page and another one
saved, the app showed More and not the Overview, where the steps looked for the balance. 94b8725 put the three
right, and the first package was never handed out (`*.first-build.log`). The steps needed one correction of
their own: `scroll_to` closes an open keyboard before it swipes.

Not run on an emulator: what is listed for 0.5.0 and 0.5.1 below; a payment that has waited for over an hour;
a full list of payments that did not go through; a refused emptying of a paper address. These three have
tests on the screen in the suite, against the stand-in on a real node.

## What was run for 0.5.1

On 14 November 2026 the signed package of 0.5.1 (SHA-256 `1dbb549c1b801f0d6867e958671c18683a9c50b2941dc972c17b101ecc88c4ce`)
went through all five runs on Android 12, 14 and 16: `t02_wallet.py` with a node of one's own and through the
gateway (0.1.2), `t03_lock.py`, `t04_not_gone_through.py`, and `t05_upgrade.py` over the signed package of
0.5.0. Every run ended with its line of success: `ALL 12 STEPS PASSED`, `LOCK STEPS PASSED: 4`,
`NOT GONE THROUGH: ALL 6 STEPS PASSED`, `UPGRADE: ALL 4 STEPS PASSED (0.5.0 -> 0.5.1)`. The logs are in
`../evidence-0.5.1`.

The steps that are new: the first screen must name no public node; the whole paper is checked against the new
wallet, with its own words and with valid words of another wallet; a wallet is brought back from words of which
the last was typed wrongly, after the app named the word and its place and kept what was typed; and the paper
written with 0.5.0 is checked with 0.5.1.

Three runs failed on their first try. None of the causes was in the app. The three logs are kept beside the
others (`*.first-try.log`).

- The run with a node of one's own stopped at its last look on Android 14 and on Android 16. The notice "The
  wallet is ready" stands at the top of the More page, and on these two versions the screen is read without
  what has scrolled out of sight. The wallet had been brought back by then. The step now goes to the top of
  the page before it looks (`top` in `t02_wallet.py`). On Android 12 the run had passed; it was repeated with
  the changed step like the other two.
- The upgrade run stopped on Android 12 inside the steps for the old package, before 0.5.1 was installed: the
  camera permission of 0.5.0 was on the screen. The swipe that brings the Send page to its top had arrived
  as a tap on "Scan a QR code". The likely reason, not proven: `input swipe` sends no movement when the first
  touch is not delivered within the swipe's 250 ms, which can happen on a machine that runs three emulators,
  and a touch and a release with no movement between them count as a tap. The run was repeated unchanged and
  passed. A swipe sent as touch, movement and release would not have this weakness; the steps do not do that
  yet.

Before these runs the steps had needed three corrections for the new screens. More now has two fields called
"Recovery words", so a field is found by the title of its block (`field_of`). On Android 14 and 16 an answer
above its button can be outside what is read, so the step moves the page a little between its looks
(`answer`). And the key that moves to the end of a text goes only to the end of a line, so the wrong word is
put right with Control+End (`restore_wallet_with_a_slip`).

An earlier package of 0.5.1, built before the reviewer's findings were dealt with, was used only to rehearse
these steps and was never handed out.

Not run on an emulator: what is listed for 0.5.0 below, and a second tap on "Store the wallet" (it is
covered by tests in the suite).

## What was run for 0.5.0

On 14 November 2026 the signed package of 0.5.0 (SHA-256 `a571e5014cab223dc68ace3fbca884cf8a52a53c7f227614aa05bbfce8713bca`)
went through all five runs on Android 12, 14 and 16: `t02_wallet.py` with a node of one's own and with the
public node (gateway 0.1.2), `t03_lock.py`, `t04_not_gone_through.py`, and `t05_upgrade.py` over the signed
package of 0.4.1 (SHA-256 `caeb9a13fe1ffcfc3a51ba5d0d3ae8d12162d06a817278bab81835c3778f1180`). Every run ended
with its line of success: `ALL 11 STEPS PASSED`, `LOCK STEPS PASSED: 4`, `NOT GONE THROUGH: ALL 6 STEPS PASSED`,
`UPGRADE: ALL 3 STEPS PASSED (0.4.1 -> 0.5.0)`. The logs are in `../evidence-0.5.0`.

Two things had to be put right on the way, neither of them in the app. The steps took the last line of the
first screen for hidden on Android 12, where the page ends above the phone's own bar (`visible` in `flows.py`).
And the Android 12 emulator, after it had been restarted, no longer accepted its enrolled fingerprint for keys:
making a wallet ended with the app's message that something stored in the phone cannot be read or written, and
Android's log showed the key store answering "user not authenticated" although the fingerprint had been
accepted. The signed package of 0.4.1 behaved the same on that emulator. After the screen lock was removed
(`locksettings clear --old 1234`) and `lock_and_finger.py` was run again, all runs passed. The logs of the
attempts before that are not kept here.

Not run on an emulator: the "Look further" card and "Forget this payment" (both have screen tests, and tests
against a real node, in the suite), the removal of the phone's screen lock (`t03_lock.py keygone`), and the
app in Czech with the signed package (a debug build of the same sources was looked at in Czech on Android 14).

## What was run for 0.4.1

On 14 November 2026 the signed package of 0.4.1 went through `t02_wallet.py` with a node of one's own and with
the public node (gateway 0.1.2), and through `t03_lock.py`, on Android 12, 14 and 16. Every run ended with
`ALL 11 STEPS PASSED` or `LOCK STEPS PASSED: 3`. The logs are in `../evidence-0.4.1`. The steps for the screens
of that version are kept as `flows_0_4.py`.

## What an emulator does not show

The security chip of a real phone (the emulator's Keystore is software), a real fingerprint reader, the camera,
other makers' versions of Android, and how the app looks on a real screen. Those need phones.
