# north-wallet (work in progress, nothing here is released)

A TrueNorth (NORTH) wallet for Android, built in stages. What exists on 13 November 2026:

| Module | What it is | State |
|---|---|---|
| `core` | keys, addresses and transactions (no Android code, no network code) | tested against the published vectors and a regtest node |
| `node` | a small JSON-RPC client for a TrueNorth Core node of one's own | tested against a regtest node set up like the real one |
| `app`  | **North Watch 0.1**: a watch-only Android app | builds; screens tested on the desktop; **not yet run on a phone** |

## North Watch 0.1 (watch only)

It shows what the watched addresses hold, as the user's own node sees it: total, what can be spent, block
rewards that are still maturing, and every unspent output. It has no keys and cannot send coins.

- It talks to one node only, over https, with a node account that may read the chain and pass on finished
  transactions and nothing else (`rpcauth` plus `rpcwhitelist`; see `node/src/test` for the exact list).
- The setup (node address, user, password, addresses) comes as one small JSON file, `north-watch-setup/1`.
  The password is never shown on the screen. It is stored encrypted with a key from the Android Keystore,
  in the app's private storage, and the app is excluded from backups.
- The node is trusted for what exists, not for what is ours: an answer with an output that does not pay to
  a watched address is refused as a whole, and so is a node that works on another network.
- One permission: INTERNET. No analytics, no other server, no code downloaded at run time.
- A self-test button derives the address of the public BIP39 test phrase and compares it with the published
  one. It shows whether the key code and the native libsecp256k1 work on that phone. It uses no key of the user.

What it is not: reviewed, signed with a release key, or minified. The file `app-debug.apk` is a debug build
(larger, slower, and readable by anyone who can attach a debugger to the unlocked phone). That is acceptable
for a password that can only read; it would not be for keys that can spend.

## Code

- `core/src/main/kotlin/north/core/`: `Chain.kt` (network parameters, v30.5.4), `Qrh.kt` (P2QRH commitment,
  script, address; strict decoding of addresses), `Account.kt` (BIP39 words to the keys of m/88h/COINh/0h),
  `Spend.kt` (builds and signs a transaction; exact size before signing).
- `node/src/main/kotlin/north/node/NodeClient.kt`: balance by `scantxoutset`, `testmempoolaccept`,
  `sendrawtransaction`, confirmations. https, or plain http only to a private or Tailscale address.
- `app/src/main/kotlin/io/github/severka_monitor/northwatch/`: `Setup.kt` (the setup file, every field
  checked), `Store.kt` (the sealed file and the Keystore key), `WatchModel.kt` (state and the checks on the
  node's answer), `MainActivity.kt` (the screens), `Format.kt` (amounts as text).

The elliptic-curve code is libsecp256k1 (fr.acinq.secp256k1:secp256k1-kmp 0.24.0, JNI). BIP32, BIP39, bech32,
transaction serialization and the BIP-341 signature hash come from fr.acinq.bitcoin:bitcoin-kmp 0.32.0. The
screens use Jetpack Compose (BOM 2026.06.01, the last one made for Android Gradle plugin 8.x and API 36).

## Tests

`TN_BIN=/path/to/official/bin gradle :core:test :node:test :app:testDebugUnitTest :app:lintDebug`

- `core` `VectorsTest`: byte for byte against `p2qrh-test-vectors.json` of the cold address repository.
- `core` `RegtestAcceptanceTest`: a transaction built here is accepted, mined and found on a regtest chain.
- `node` `NodeClientTest`: balance, test, send and confirmations through a restricted user; other calls refused.
- `app` `SetupTest`, `StoreTest`, `FormatTest`: the setup file (https only, every address checked against the
  network), the sealed file (a changed byte or another key is refused), amounts.
- `app` `ScreenTest`: the real activity run on the desktop by Robolectric: setup from the clipboard (the
  password is not on the screen, the clipboard is emptied), an unreachable node, removing the setup, the
  self-test, and the balance of a real regtest node with a refresh, a wrong network and a wrong password.

Not covered by any test here, because it needs a phone: how the screens look, the Android Keystore, the file
picker, deleting the setup file after import, the native library for phones, TLS to a real node.

## Not done yet

Making new words on the device, coin selection and fees, sending, legacy base58 recipients, a release build
with its own signing key, dependency verification, and the two reviews that every release will go through.
