# Polarka Wallet

A wallet for TrueNorth (NORTH) on Android. It keeps its keys in the phone, signs there, and asks a wallet
server or a TrueNorth Core node what its addresses hold. The project runs no server of its own. There is no
account and no analytics.

**State: test build 0.6.0, a pre-release.** The wallet has been checked by its author and, in six looks, by an
independent reviewer, an AI model that had not seen the code being written (`review/independent-review-0.3.1`,
`review/independent-review-0.4.0`, `review/independent-review-0.4.2`, `review/independent-review-0.5.1` and
`review/independent-review-0.6.0`; `review/changes-0.6.0.md` says what the last version changed). The sixth
look found that a wallet server could have made the wallet pay the same thing twice. That was put right
before this build, and the reviewer looked at the fixes. All of that was AI-assisted; a review
by an independent human expert is still recommended. Until then it is for small amounts.
It is an independent community project and not a product of the TrueNorth developers.

## Getting the package

The signed package is on the Releases page of this repository, marked as a pre-release. It needs Android 12
or newer and is installed from the file; Android asks once whether the app that opens the file may install
apps. A later package installs over an earlier one and the wallet stays where it is.

| | |
|---|---|
| File | `polarka-wallet-0.6.0.apk`, built from commit `94b8725` (tag `v0.6.0`) |
| SHA-256 of the file | `7c0968934f4b08748d30cd1c93f76aade95155e84a7be38a996ebe97925f56c4` |
| SHA-256 of the signer's certificate | `f441275239c880d0855a40525ca2449a2aa4992b4d4c2e63143d36c088ce103b` |

Compare both before installing: `shasum -a 256 polarka-wallet-0.6.0.apk` (or `sha256sum`), and
`apksigner verify --print-certs polarka-wallet-0.6.0.apk` from the Android build tools. Every later package
carries the same certificate, and a phone refuses to put a package with another certificate over this one.
The project publishes the package in this one place: no app store and no website offers it.

Write the recovery words on paper before any coins arrive. The app is excluded from backups, so the words
are the only way back to the wallet.

What you find is welcome as an issue in this repository. If it puts coins at risk, please report it
privately first ("Report a vulnerability" on the Security page of the repository).

## What it does

- Makes a wallet from 24 or 12 recovery words (BIP39), as its owner chooses, or brings one back from its
  words. However long the wallet was in use, its coins are found again: used keys are searched until twenty
  unused ones follow. The app looks at the first 200 addresses of each kind by itself (40 with a wallet
  server or a public node) and asks its owner before it looks further.
- Checks a paper in full: the words typed from the paper are compared with the wallet in the phone, without
  opening the stored words. When a wallet is made the app asks back three of its words, which would miss
  seven of eight copying mistakes with 24 words and three of four with 12; this check misses none. Words that are not valid are answered with what
  is wrong: which word is not on the list (by its place), how many words there are, or that they do not fit
  each other.
- Receives to P2QRH addresses (`north1z...`), a fresh one whenever the last one has been used; shows a QR code.
- Sends: the payment is worked out, shown, and signed only after the owner's fingerprint or screen lock.
  The fee is exact before signing. Addresses can be pasted or read from a QR code.
- Shows the history, block rewards that are still maturing, and payments that wait for a block.
- Watches addresses without their keys (for example a paper address that is being mined to).
- Empties a paper address whose words are typed in for that one use.
- A mining page (with a node: network hash rate, own share, expected blocks) and optional notifications
  about a new reward or payment.
- A self-test that checks the key code and the native library on the phone against published vectors.

## Where the wallet asks

The wallet talks to one wallet server or to one node, over https only.

- **A wallet server.** Nothing to set up. The first screen offers the public server of the Northbound project
  (`https://api.northboundwallet.org`, github.com/nova9/northbound). It is named there at its author's
  request and is not run by this project. The address of another server with the same API (version 1) can be
  typed in its place or come in a setup file (`polarka-setup/3`). The wallet names its addresses to the
  server, at most a hundred in one request, and hands it signed payments; it never sends the key that shows
  the whole wallet. The server sees the addresses of the wallet together and the phone's internet address,
  and it could show a false balance, keep an incoming payment from its owner or hold a payment back. It
  cannot take coins: a transaction built on false figures is not valid, and keys never leave the phone.
  `docs/northbound-api.md` says what the wallet takes for granted about such a server.
- **A node of one's own.** The node gets a user that may only read the chain and pass on finished transactions
  (`rpcauth` with `rpcwhitelist`; the list is in `node/src/test`). The setup comes as a small JSON file
  (`north-watch-setup/1` or `polarka-setup/2`). The password is never shown, is stored encrypted, and the
  file can be deleted after import. Nobody else learns the addresses.
- **A public node.** A TrueNorth Core node behind the gateway of the `polarka-node` repository (not published), named in a
  setup file without an account (`polarka-setup/2`). The project runs none, and since 0.6 the first screen
  does not offer one. The wallet names its addresses to such a node one by one; what the node sees and could
  do is what is said of a wallet server above.

What a server or a node says is trusted for what exists, never for what is ours: an answer with an output
that does not pay to the wallet's own scripts is refused as a whole, and so are amounts that cannot exist,
malformed ids, a node on another network, a server whose index is not at the tip of its node, and answers
that are too long. A wallet server does not say which network it works for; one that works for another
refuses the wallet's addresses, and the app then says so.

Through a wallet server some things are known less well than through a node:

- The history comes summed up by the server, each line against the addresses of one request, and a request
  takes a hundred addresses. A wallet that has used more than fifty keys of a kind is asked about in parts.
  A payment whose coins all sit in one part is shown exactly. One whose coins sit in several parts is shown
  without its fee. One of which a part got back at least as much as it put in cannot be told from the
  server's lines: it is left out, and the History page says that the history is not whole. The balance and
  the coins are complete in every case.
- A block reward is known as one only while its coin is unspent.
- The server says whether it knows a transaction, not whether that is in a block. A payment that the server
  knows and the history does not list is taken to wait for a block.
- An amount on its way to the wallet in a transaction that waits for a block is shown apart from the balance.
- There is no hash rate of the network, so the mining page shows no share and no expected blocks.

The wallet does not check the proof of work behind the blocks a node or a server shows. One that lies can
therefore show a false balance or a false history, and it can make addresses look used. Three things keep
the damage small. The wallet moves its address numbers only within the addresses it looks at, asks its owner
before it looks further, and can work the numbers out again with another node or server (More); a wallet
brought back from its words looks at the same addresses. A payment counts as refused only when the node
itself says no to the transaction at the first try; a lost answer, or an error of a gateway or web server in
front of the node, leaves it written down as "outcome not known" with its coins set aside, and no other
payment is made until that is settled. And a waiting
payment is reported as gone only when it is in no block, the node does not hold it, nothing else is spending
its coins, it is at least a minute old, and two looks in a row agree. Even then it is not forgotten: its
transaction could still reach a block from another node, so the next payment is built on one of its coins,
and only one of the two can ever be in a block.

Whoever is shown a signed transaction can keep it and send it later, whatever they answered. Only the
owner's own node is taken at its word: it is asked before a payment is written down, and what it refuses is
forgotten. A wallet server and a public node see a payment only after it has been written down, and a
payment that they refuse is kept as one that did not go through, with the next payment built on one of its
coins. Such a payment is let go when a block shows it after all, when a block shows a later payment of the
wallet's that spends one of its coins, or when its owner says so. Through a wallet server nothing else lets
it go: a server does not say what spent a coin, and a coin that it merely does not list proves nothing.
While such a payment has no coin among those the server lists, the wallet makes no payment at all.

## Keys and what is stored

- The recovery words are sealed with a key that lives in the Android Keystore (the phone's security chip
  where there is one) and is released only after the owner unlocks it, once per use. The app cannot read the
  words on its own. They are never shown again after the wallet is made, never logged, never sent.
- Words that are typed (to bring a wallet back, to check a paper, to look at a paper address) are held in the
  field they are typed into and are not written into the saved state of the screen. They go when they have
  done their work, when the page is left, and when the app is opened again after more than a minute in the
  background; a message that names a mistyped word goes with them. One case is different: the words of a
  paper address that has been looked at stay in the app's memory, also on other pages, until the address is
  emptied, the look is dropped, or the app is opened again after more than a minute in the background.
- The node setup and the wallet's public information are sealed with a second Keystore key. The app is excluded
  from backups; nothing travels to a cloud or to another phone.
- Screens cannot be captured; no other app may draw over the wallet; from Android 14 on, the screens with words
  are hidden from accessibility services that are not screen readers.
- Permissions: INTERNET, USE_BIOMETRIC, CAMERA (asked when a code is scanned), POST_NOTIFICATIONS (asked when
  switched on), RECEIVE_BOOT_COMPLETED and ACCESS_NETWORK_STATE (for the periodic look at the node),
  HIDE_OVERLAY_WINDOWS. No storage, contacts, location or microphone.

## Limits

- A payment that has not reached a block yet is not shown to its receiver; it appears with the first block.
- One account, P2QRH addresses only, no fee estimate from the node or the server (three fixed fee rates).
- A wallet server lists at most 10,000 coins in one answer and refuses a request beyond that; the app then
  shows the server's words and no balance. A wallet that is mined to has to join its coins before it gets
  there, or use a node.
- With a wallet server the wallet depends on that one server: while it is out of service nothing is shown
  and nothing can be sent. The coins are not touched by that, and the wallet can be connected elsewhere.
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
  phone: a wallet brought back from its words elsewhere does not have it, and removing the wallet drops it.
  And the owner can tell the app to forget such a payment; after that nothing stands against it. A node that
  does not show the truth can undo the safeguard for one payment by showing a made-up block that spends its
  coin. A wallet server cannot do it that way. All it can show to make the wallet let such a payment go is
  that payment itself in a block, or a later payment of the wallet's that spends one of its coins, and those
  two can never both reach a block. What a server that does not show the truth can do is to call a payment
  done that is not.
- With a wallet server, a payment that did not go through and whose coin was then spent in another way (from
  another phone with the same words) stays on the Overview, and no payment is made until its owner forgets
  it. Before doing that the wallet should be looked at through another server or a node.
- A payment that waits for a block is not something the next payment stands against. Whoever was handed a
  payment can hold it back, and an owner who then pays the same thing again can pay twice. After an hour of
  waiting the app says so, on the Overview and where the next payment is confirmed.
- When one look at the node has two things to say about payments, one notice is shown. The Overview and the
  History show both payments.
- Version 0.3.1 was tried on one real phone (Android 16), and 0.6.0 was installed there over 0.5.1: the
  wallet was there and worked with its owner's node. The versions since 0.3.1 were tried on emulators with
  Android 12, 14 and 16; `review/emulator/README.md` says what was run for each of them. No payment has been
  made through a wallet server on a real phone yet.

## Code

| Module | What it is |
|---|---|
| `core` | keys, addresses, coin selection and signing; no Android code and no network code |
| `node` | a small JSON-RPC client for TrueNorth Core and a client for a wallet server (`ServerClient.kt`), with limits on everything either may send |
| `app` | the Android app: `Setup.kt`, `Store.kt`, `Gateway.kt` with `Engine.kt` (a node) and `ServerEngine.kt` (a wallet server), `WalletModel.kt`, the screens |

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

187 tests. A test that cannot run (no node programs, or no `gateway.py` of polarka-node next to this
repository or named by `POLARKA_GATEWAY`) is reported as skipped; for a release none may be skipped. `polarka-node` is not published, so with this
repository alone the tests that need it (in `PublicNodeTest` and `RetestModelTest`) are skipped. Every library is pinned by its SHA-256 in `gradle/verification-metadata.xml`:
the build stops if one differs from the day it was first downloaded. The pins were made by Gradle on that day
(trust on first download); signatures of the libraries are not verified.

A release is signed with a key that lives outside this repository and is opened only when a release is built.
`review/README.md` describes the checks made before a release and how to repeat them.

## Licence

MIT, see `LICENSE`. There is no warranty: see the licence, and the state described at the top.
