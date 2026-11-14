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
| `bench.py` | the test network: a regtest TrueNorth Core, the public gateway (`gateway.py` of polarka-node), TLS in front of both |
| `emu.py` | reads the screen as text and touches it, through adb |
| `flows.py` | what a user does in the wallet, as steps (the screens since 0.5.0) |
| `flows_0_4.py` | the same steps on the screens of 0.4.1; `t05_upgrade.py` needs them for the older package |
| `t01_selftest.py` | starts the app and runs its self-test |
| `t02_wallet.py` | the whole way: setup, new wallet, the whole paper checked against it, receive, send, the app closed while a payment waits, a paper address emptied, wallet removed and brought back after a slip in a word was named |
| `t03_lock.py` | the lock after a minute away, also when Android rebuilds the screen meanwhile; the lock in the top bar; optionally what the app says after the screen lock of the phone is removed |
| `t04_not_gone_through.py` | a payment that is kept from the node: "outcome not known" and no other payment meanwhile, then "did not go through", the next payment built on its coin, and the kept transaction refused by the node afterwards |
| `t05_upgrade.py` | the package installed over the one before it (0.5.0, or 0.4.1 with `flows_0_4.py`): the same wallet opens, still knows a waiting payment, signs with the words the older package stored, and finds the paper written with the older package right |
| `matrix.sh` | the five runs for one emulator, one after the other |

## Why it is built this way

- The wallet forbids screenshots, so the screen is read from the accessibility tree (`uiautomator dump`).
  That is also why the scripts match English texts: the emulators run in English.
- The wallet trusts only the system's certificate authorities, as every app does by default. Instead of a
  special build that trusts a test certificate, the emulator is made to trust the test authority. The package
  under test is then the very file that would be handed out. It needs an emulator image without Google Play
  (those allow `adb root`).
- The emulator reaches the host as 10.0.2.2. The bench listens there: 18651 goes straight to the node (a node
  of one's own, user `phone`, limited like a real phone account), 18652 goes through the gateway (a public node).
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

`bench.py` needs `TN_BIN` (the folder with the official `truenorthd` and `truenorth-cli`) and finds the gateway
through `POLARKA_GATEWAY`. The other paths inside the scripts assume the folder layout of the machine they were
written on (`~/north-build`); change `emu-env.sh`, `emu-start.sh`, `make_ca.sh`, `trust_ca.sh`, `matrix.sh` and
the first lines of `emu.py` for another one. An emulator that was restarted shows its lock screen and has
forgotten the test authority: unlock it and run `trust_ca.sh` again.

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
