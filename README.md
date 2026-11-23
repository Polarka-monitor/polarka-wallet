# Polarka Wallet

A wallet for TrueNorth (NORTH) on Android. It keeps its keys in the phone, signs there, and asks a wallet
server or a TrueNorth Core node what its addresses hold. There is no account and no analytics.

**State: test build 0.7.2, a pre-release.** The wallet has been checked by its author, in eight looks by
independent reviewers that were AI models and had not seen the code being written (`review/independent-review-0.3.1`,
`-0.4.0`, `-0.4.2`, `-0.5.1`, `-0.6.0`, `-0.6.1` and `-0.7.0`), and in a ninth look by people: an independent
security review of 0.7.1 by people outside this project, whose names are not given here. That review found no
critical and no high fault, and nothing through which a server, a node or another app could take the recovery
words, put another address in place of the one typed, or take coins. It found four medium and five low faults;
0.7.2 puts right the four medium ones and the three low ones that concern the app, and the other two are put
right in the server software (`review/changes-0.7.md`). Nobody independent has looked at those fixes yet. Like
every release it comes without any warranty (`LICENSE`).

0.7 adds three things to 0.6.1. The first screen offers a second wallet server beside the Northbound project's:
Polarka's own, at `node.polarka.org`, run by the authors of this app. The owner can ask for one fixed receiving
address in place of a new one for each payment. And the app comes in two editions from one source (below);
the public one is the one that is published. 0.6.1 itself followed a change of the public Northbound server
(`review/changes-0.6.1.md`, and the seventh look in `review/independent-review-0.6.1`).
It is an independent community project and not a product of the TrueNorth developers.

## Getting the package

The signed package of the public edition is on https://polarka.org/download/, marked as a test build. The
source is on https://polarka.org/source/#polarka-wallet and mirrored at github.com/Polarka-monitor. It needs
Android 12 or newer and is installed from the file; Android asks once whether the app that opens the file may
install apps. A later package installs over an earlier one and the wallet stays where it is.

| | |
|---|---|
| File | `polarka-wallet-0.7.2.apk`, built from the commit that the tag `v0.7.2` names |
| SHA-256 of the file | written here by the commit after that one, and on the download page: a commit cannot name the checksum of a package that is built from it |
| SHA-256 of the signer's certificate | `f441275239c880d0855a40525ca2449a2aa4992b4d4c2e63143d36c088ce103b` |

Compare both before installing: `shasum -a 256 polarka-wallet-0.7.2.apk` (or `sha256sum`), and
`apksigner verify --print-certs polarka-wallet-0.7.2.apk` from the Android build tools. Every later package
carries the same certificate, and a phone refuses to put a package with another certificate over this one.
No app store offers the package.

Write the recovery words on paper before any coins arrive. The app is excluded from backups, so the words
are the only way back to the wallet.

What you find is welcome as an issue at the mirror. If it puts coins at risk, please write first only that
you found something, to @Polarka_monitor on X, as https://polarka.org asks; the details are then passed on
privately.

## Two editions

One source makes two packages (`productFlavors` in `app/build.gradle.kts`):

- **public**, the one that is published: a new receiving address for each payment unless the owner asks for
  a fixed one; no faucet.
- **personal**, for the phone of the project's owner: one fixed receiving address unless the owner switches
  it off, and on the main network a button that asks Polarka's faucet (`faucet.polarka.org`) for a first coin
  to that address. The phone does the faucet's work (SHA-256 with leading zero bits, at most 2^28 tries, ten
  minutes at most, and it can be stopped) and sends the faucet the address and, by asking, the phone's
  internet address. The faucet's code and texts are in `app/src/personal` alone, so the public package holds
  none of them.

To Android both are the same app (one id, one signing key), so that the owner's phone keeps its wallet when it
moves from one to the other. A package put in the wrong place would therefore install without a word. Before
a package is published, `review/static-checks.sh` shows which edition it is (`06-edition.txt`).

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
- Receives to P2QRH addresses (`north1z...`), a fresh one whenever the last one has been used, or one fixed
  address if its owner asks for it (More, Wallet in this phone); shows a QR code. A fixed address costs
  privacy: every payer sees what the others paid to it, and payments made with its coins can be linked to it.
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

- **A wallet server.** Nothing to set up. The first screen offers two. The one filled in is the public server
  of the Northbound project (`https://api.northboundwallet.org`, github.com/nova9/northbound); it is named
  there at its author's request and is not run by this project. The other is Polarka's own
  (`https://node.polarka.org`): walletd of polarka-agent on a node of the authors of this app. The screen says
  whose the server in the field is for exactly these two addresses, and of any other that the app does not
  know who runs it; it says so again before the setup is saved, and the More page names it. The address of
  another server with the same API (version 1) can be typed in its place or come in a setup file
  (`polarka-setup/3`). The wallet names its addresses to the
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

- The history comes summed up by the server: for every movement, what the wallet's addresses have more or
  less by it. A payment shows as what the wallet lost, with the fee of the whole transaction beside it. Who
  was paid, and what was received and spent within one transaction, is not shown. A request takes a hundred
  addresses, so a wallet that has used more than fifty keys of a kind is asked about in parts, and what the
  parts say adds up exactly. A history longer than the app reads in one look (2,000 movements for each
  part) says on the History page that it is not whole. The balance and the coins are complete in every case.
- A block reward is known by having no fee. Where coins of somebody else were spent in the same transaction
  as the wallet's, the fee shown beside the payment is still that of the whole transaction.
- The server says whether it knows a transaction, not whether that is in a block. A payment that the server
  knows and the history does not list is taken to wait for a block.
- An amount on its way to the wallet in a transaction that waits for a block is shown apart from the balance.
  For a payment of the wallet's own that is its change, which the app works out from what it wrote down when
  the payment was made. A payment made with 0.6.0 or earlier has no such note; its change shows with its block.
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
- Through a wallet server the app reads at most 20,000 coins for a wallet. With more it says so and shows no
  balance. A wallet that is mined to has to join its coins before it gets there, or use a node.
- A wallet server can change the shape of its answers, and nothing in an answer says that it has. The app
  then refuses what it cannot read and shows nothing, as 0.6.0 does since 15 November 2026 with the public
  Northbound server, until a version that reads the new answers is installed. A node of one's own does not
  change under the wallet in that way.
- Through a wallet server every look reads the whole history again, and all of a look has to come from one
  picture of the chain. With the Northbound server that picture changes with every block and with every
  transaction that begins to wait anywhere on the network. When it changes during a look the app looks
  again, three times at the most. On 15 November 2026 that was enough in every look that was tried, from a
  computer and from an emulator; it was not measured on a phone. On a much busier network a wallet with a
  long history, one that is mined to for example, may get no look that way; the app then says that the
  picture kept changing and shows nothing new until a quieter moment. Coins are not touched by that.
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
  Android 12, 14 and 16; `review/emulator/README.md` says what was run for each of them. On 16 November
  2026 0.6.1 was installed on that phone over 0.6.0, and payments were sent from the wallet and received,
  through its owner's node and through the public Northbound server. All went through. That was one test by
  hand: no log was kept of it and nothing was measured. 0.7.1 has not yet been tried on an emulator or on a
  real phone.
- Polarka's server at `node.polarka.org` answers one request at a time and has no limit of its own on how
  often it is asked; somebody can make it slow or unreachable. The wallet then shows an error and judges
  nothing, and it can be connected to Northbound's server or a node instead.

## Code

| Module | What it is |
|---|---|
| `core` | keys, addresses, coin selection and signing; no Android code and no network code |
| `node` | a small JSON-RPC client for TrueNorth Core and a client for a wallet server (`ServerClient.kt`), with limits on everything either may send |
| `app` | the Android app: `Setup.kt`, `Store.kt`, `Gateway.kt` with `Engine.kt` (a node) and `ServerEngine.kt` (a wallet server), `WalletModel.kt`, the screens; in `app/src/personal` the faucet of the personal edition, in `app/src/public` the empty place where the public edition has none |

The elliptic-curve code is libsecp256k1 (`fr.acinq.secp256k1:secp256k1-kmp` 0.24.0, JNI). BIP32, BIP39, bech32,
transaction serialization and the BIP-341 signature hash come from `fr.acinq.bitcoin:bitcoin-kmp` 0.32.0. The
screens use Jetpack Compose; QR codes are read and drawn with zxing. `THIRD-PARTY.md` lists the libraries and
their licences.

## Building and testing

JDK 21, Gradle 8.14.3 and the Android SDK (platform 36). `TN_BIN` names a folder with the official `truenorthd`
and `truenorth-cli` of TrueNorth Core v30.5.4; the tests start their own regtest node from it and never touch
a real network.

    TN_BIN=/path/to/bin gradle :core:test :node:test :app:testPublicDebugUnitTest :app:testPersonalDebugUnitTest \
        :app:lintPublicDebug :app:lintPersonalDebug
    sh review/tests-summary.sh

210 tests with the public edition (34 in `core`, 31 in `node`, 145 in the app) and 218 with the personal one
(its app has 153: the faucet's tests and screen tests of its own in place of the public edition's). A test that cannot run (no node programs, or no `gateway.py` of polarka-node next to this
repository or named by `POLARKA_GATEWAY`) is reported as skipped; for a release none may be skipped. `polarka-node` is not published, so with this
repository alone the tests that need it (in `PublicNodeTest` and `RetestModelTest`) are skipped. Every library is pinned by its SHA-256 in `gradle/verification-metadata.xml`:
the build stops if one differs from the day it was first downloaded. The pins were made by Gradle on that day
(trust on first download); signatures of the libraries are not verified.

A release is signed with a key that lives outside this repository and is opened only when a release is built.
`review/README.md` describes the checks made before a release and how to repeat them.

## Licence

MIT, see `LICENSE`. There is no warranty: see the licence, and the state described at the top.
