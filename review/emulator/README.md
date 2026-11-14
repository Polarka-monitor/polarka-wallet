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
| `bench.py` | the test network: a regtest TrueNorth Core, the public gateway (`gateway.py` of polarka-node), TLS in front of both |
| `emu.py` | reads the screen as text and touches it, through adb |
| `flows.py` | what a user does in the wallet, as steps |
| `t01_selftest.py` | starts the app and runs its self-test |
| `t02_wallet.py` | the whole way: setup, new wallet, receive, send, the app closed while a payment waits, a paper address emptied, wallet removed and brought back |
| `t03_lock.py` | the lock after a minute away, also when Android rebuilds the screen meanwhile; optionally what the app says after the screen lock of the phone is removed |

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

## A run

    . ./emu-env.sh && zsh emu-create.sh            # once
    zsh emu-start.sh 34 5560                       # Android 14 on port 5560
    python3 lock_and_finger.py emulator-5560
    zsh make_ca.sh && zsh trust_ca.sh emulator-5560
    TN_BIN=/folder/with/truenorthd nohup python3 bench.py start &
    python3 t02_wallet.py emulator-5560 /path/to/polarka-wallet.apk own
    python3 t02_wallet.py emulator-5560 /path/to/polarka-wallet.apk public
    python3 t03_lock.py emulator-5560

`bench.py` needs `TN_BIN` (the folder with the official `truenorthd` and `truenorth-cli`) and finds the gateway
through `POLARKA_GATEWAY`. The other paths inside the scripts assume the folder layout of the machine they were
written on (`~/north-build`); change `emu-env.sh`, `emu-start.sh`, `make_ca.sh`, `trust_ca.sh` and the first
lines of `emu.py` for another one.

## What was run for 0.4.1

On 14 November 2026 the signed package of 0.4.1 (SHA-256 `caeb9a13fe1ffcfc3a51ba5d0d3ae8d12162d06a817278bab81835c3778f1180`)
went through `t02_wallet.py` with a node of one's own and with the public node (gateway 0.1.2), and through
`t03_lock.py`, on Android 12, 14 and 16. Every run ended with `ALL 11 STEPS PASSED` or `LOCK STEPS PASSED: 3`.
The logs are in `../evidence-0.4.1`. Not run on an emulator: the "Look further" card (it has a screen test
and a test against a real node in the suite), and the removal of the phone's screen lock (`t03_lock.py keygone`).

## What an emulator does not show

The security chip of a real phone (the emulator's Keystore is software), a real fingerprint reader, the camera,
other makers' versions of Android, and how the app looks. Those need phones.
