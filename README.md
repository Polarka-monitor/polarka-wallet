# Polarka Wallet

A wallet for TrueNorth (NORTH) on Android. It keeps its keys in the phone, signs there, and asks a TrueNorth
Core node what the addresses hold. There is no server of the wallet's own, no account and no analytics.

**State: test build 0.4.2, not released.** The wallet has been checked by its author and, twice, by an
independent reviewer that had not seen the code being written (`review/independent-review-0.3.1` and
`review/independent-review-0.4.0`). All of that was AI-assisted; a review by an independent human expert is
still recommended. Until then it is for small amounts.
It is an independent community project and not a product of the TrueNorth developers.

## What it does

- Makes a wallet from 24 recovery words (BIP39), or brings one back from its words. However long the wallet
  was in use, its coins are found again: used keys are searched until twenty unused ones follow. The app
  looks at the first 200 addresses of each kind by itself (40 with a public node) and asks its owner before
  it looks further.
- Receives to P2QRH addresses (`north1z...`), a fresh one whenever the last one has been used; shows a QR code.
- Sends: the payment is worked out, shown, and signed only after the owner's fingerprint or screen lock.
  The fee is exact before signing. Addresses can be pasted or read from a QR code.
- Shows the history, block rewards that are still maturing, and payments that wait for a block.
- Watches addresses without their keys (for example a paper address that is being mined to).
- Empties a paper address whose words are typed in for that one use.
- A mining page (network hash rate, own share, expected blocks) and optional notifications about a new
  reward or payment.
- A self-test that checks the key code and the native library on the phone against published vectors.

## The node

The wallet talks to one node, over https only.

- **A public node.** Nothing to set up: the address of the node is enough. The wallet names its addresses to
  the node one by one and never sends the key that shows the whole wallet. A public node sees those addresses
  and the phone's internet address, and it could show a false balance or hold a payment back. It cannot take
  coins: a transaction built on false figures is not valid, and keys never leave the phone. The gateway that
  makes a node public is in the `polarka-node` repository.
- **A node of one's own.** The node gets a user that may only read the chain and pass on finished transactions
  (`rpcauth` with `rpcwhitelist`; the list is in `node/src/test`). The setup comes as a small JSON file
  (`north-watch-setup/1` or `polarka-setup/2`). The password is never shown, is stored encrypted, and the
  file can be deleted after import. Nobody else learns the addresses.

What the node says is trusted for what exists, never for what is ours: an answer with an output that does not
pay to the wallet's own scripts is refused as a whole, and so are amounts that cannot exist, malformed ids,
a node on another network, and answers that are too long.

The wallet does not check the proof of work behind the blocks a node shows. A node that lies can therefore
show a false balance or a false history, and it can make addresses look used. Three things keep the damage
small. The wallet moves its address numbers only within the addresses it looks at, asks its owner before it
looks further, and can work the numbers out again with another node (More). A payment counts as refused only
when the node itself says no to the transaction; a lost answer, or an error of a gateway or web server in
front of the node, leaves it written down as "outcome not known" with its coins set aside. And a waiting
payment is reported as gone only when it is in no block, the node does not hold it, nothing else is spending
its coins, it is at least a minute old, and two looks in a row agree. Even then it is not forgotten: its
transaction could still reach a block from another node, so the next payment is built on one of its coins,
and only one of the two can ever be in a block.

## Keys and what is stored

- The recovery words are sealed with a key that lives in the Android Keystore (the phone's security chip
  where there is one) and is released only after the owner unlocks it, once per use. The app cannot read the
  words on its own. They are never shown again after the wallet is made, never logged, never sent.
- The node setup and the wallet's public information are sealed with a second Keystore key. The app is excluded
  from backups; nothing travels to a cloud or to another phone.
- Screens cannot be captured; no other app may draw over the wallet; from Android 14 on, the screens with words
  are hidden from accessibility services that are not screen readers.
- Permissions: INTERNET, USE_BIOMETRIC, CAMERA (asked when a code is scanned), POST_NOTIFICATIONS (asked when
  switched on), RECEIVE_BOOT_COMPLETED and ACCESS_NETWORK_STATE (for the periodic look at the node),
  HIDE_OVERLAY_WINDOWS. No storage, contacts, location or microphone.

## Limits

- A payment that has not reached a block yet is not shown to its receiver; it appears with the first block.
- One account, P2QRH addresses only, no fee estimate from the node (three fixed fee rates).
- The price of a coin is typed in by hand; nothing is looked up.
- Before a payment the node is asked about at most 100 of the chosen coins whether a waiting payment already
  uses them (a payment made on another phone with the same words); a payment with more coins is not protected
  beyond the first hundred.
- A block whose description is larger than 16 MB cannot be read, and a look that needs it fails. Today's
  blocks are far smaller.
- "It did not go through" is said from what one node shows. A payment that this node no longer holds can still
  be held by other nodes, for example after the node was restarted without its waiting transactions, and it
  can then still arrive. The wallet keeps such a payment on the Overview and builds the next payment on one
  of its coins, so that the two can never both arrive; the entry goes when a block shows the old payment
  after all, or shows one of its coins spent by something else. Two things remain. This memory lives in the
  phone: a wallet brought back from its words elsewhere does not have it. And the owner can tell the app to
  forget such a payment; after that nothing stands against it.
- Tried on one real phone (Android 16) and on emulators with Android 12, 14 and 16.

## Code

| Module | What it is |
|---|---|
| `core` | keys, addresses, coin selection and signing; no Android code and no network code |
| `node` | a small JSON-RPC client for TrueNorth Core, with limits on everything a node may send |
| `app` | the Android app: `Setup.kt`, `Store.kt`, `Engine.kt`, `WalletModel.kt`, the screens |

The elliptic-curve code is libsecp256k1 (`fr.acinq.secp256k1:secp256k1-kmp` 0.24.0, JNI). BIP32, BIP39, bech32,
transaction serialization and the BIP-341 signature hash come from `fr.acinq.bitcoin:bitcoin-kmp` 0.32.0. The
screens use Jetpack Compose; QR codes are read and drawn with zxing. `THIRD-PARTY.md` lists the libraries and
their licences.

## Building and testing

JDK 21, Gradle 8.14.3 and the Android SDK (platform 36). `TN_BIN` names a folder with the official `truenorthd`
and `truenorth-cli` of TrueNorth Core v30.5.4; the tests start their own regtest node from it and never touch
a real network.

    TN_BIN=/path/to/bin gradle :core:test :node:test :app:testDebugUnitTest :app:lintDebug
    sh review/tests-summary.sh

108 tests. A test that cannot run (no node programs, or no `gateway.py` of polarka-node next to this
repository or named by `POLARKA_GATEWAY`) is reported as skipped; for a release none may be skipped. Every library is pinned by its SHA-256 in `gradle/verification-metadata.xml`:
the build stops if one differs from the day it was first downloaded. The pins were made by Gradle on that day
(trust on first download); signatures of the libraries are not verified.

A release is signed with a key that lives outside this repository and is opened only when a release is built.
`review/README.md` describes the checks made before a release and how to repeat them.

## Licence

MIT, see `LICENSE`. There is no warranty: see the licence, and the state described at the top.
