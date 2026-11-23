# Review of Polarka Wallet 0.7.0 (commits 43afe35 and 6dc0f38 after v0.6.1)

Reviewer: an AI model that had not seen this code being written. This is not a review by an independent
human expert, and it is not an audit. Date: 23 November 2026, 09:04 to 09:55 CET.

Sources: the clone at 6dc0f38 in `~/north-build/audit-0.7.0/polarka-wallet` (`git diff v0.6.1 6dc0f38`); the
three signed packages in `apk/`; `polarka_agent/walletd.py` and `faucet.py` of polarka-agent at 505a3de, read
only (walletd.py was copied byte for byte into `tools/walletd-copy/`, and once more with one line changed for
regtest (N5), to be run under the wallet's tests on a private regtest node). No real server, node or phone
was contacted. My tools and logs are in `tools/` and `logs/`; my one test file is
`polarka-wallet/app/src/test/kotlin/org/polarka/wallet/ReviewerFaucetTest.kt` (untracked, not part of the
suite).

## Verdict

I found nothing in 0.7.0 that weakens what the safeguards of 0.6.1 rest on, and nothing through which a
server, the faucet or the network can make the wallet pay twice or show coins that are not there. The fixed
address changes only which address the receive page shows: the keys the wallet looks at, change, the choice
of coins, the waiting and dropped payments and the wallet file are as before, and a wallet file opens both
ways between 0.6.1 and 0.7.0, so the owner's wallet survives the upgrade (shown by reading the code, not
tried on a phone). The public package is what the commit says, and I checked that in the package itself:
its receive page has no call to the faucet, it shows a new address for each payment unless the owner
switches the fixed one on, Northbound's server is filled in, nothing read at run time can switch it to the
personal behaviour, the two editions differ in three classes and nothing else, and the signing certificate
is that of 0.6.1. Both editions pass the suite (141 tests, one skipped). The server tests that the author ran
against Northbound's server also pass on walletd, the server behind `node.polarka.org`, once one readiness
rule is relaxed for regtest.

What I found weighs little: the personal edition's faucet waits for its server with no limit as a whole,
and its work cannot be stopped (L1); the setup screen calls any typed server Northbound's, Polarka's own
included (L2); what a fixed address gives away is told only in part, and the way back points to a
"Settings" that the app does not have (L3); the README still describes 0.6.1 (L4); and nothing in the suite
tests what the editions do: a fault of my own that makes the public edition always show address 0 passed
every test that opens the receive page (L5).

Would I hold the public release back? **Not for a safety reason.** I would hold it until the texts are
right, which is a matter of hours: the README (L4) and the intro sentence of L2, because the public package
is the one that now offers the authors' own server to strangers and must say rightly whose a server is, and
with them the sentences of L3. L1 concerns only the owner's phone and can follow. For L5 I would add one
screen test per edition before the release if that can be done the same day; for these two files my check
of the dex stands in for it.

Findings: 0 critical, 0 high, 0 medium, 5 low, 5 notes.

## Findings

### L1 (low). The faucet's questions have no time limit as a whole, and its work cannot be stopped

Personal edition only. Places: `Faucet.kt` 57-90 (`post`), 39-55 (`solve`), 92-98 (`ask`); `Tabs.kt` 298-327
(`FaucetBlock`).

- `post` sets a connect timeout (15 s) and a timeout for one read (30 s), nothing for the question as a
  whole. The faucet, or whatever holds the server end of its TLS connection, can keep the button at
  "Working..." for as long as it likes by sending a byte every few seconds (someone on the network outside
  TLS can only stall the connection, which the read limit ends). Since the sixth look the wallet's own rule is that no answer is
  waited for without end (`ServerClient.DEADLINE_MS`), and `faucet.py`'s own asker keeps it (`ask`:
  `worker.join(2 * timeout + 1)` and a deadline for reading the body). `Faucet.post` does not.
- `ask` takes the faucet's `expires` as given, with no bound on how far ahead it lies, so the work is
  bounded only by `1L shl (bits + 4)`: up to 2^28 SHA-256 for one press at the most bits the phone accepts
  (24). Expected for 24 bits are 2^24. The text promises "some seconds" (`faucet_text`).
- `solve` never looks at cancellation, and `FaucetBlock` runs it and both questions in one blocking block
  on `Dispatchers.Default` in a scope of the composition. Leaving the receive page cancels that scope but
  not the work, and the claim is still sent when the work ends. Coming back shows the button enabled again
  (`busy` is state of the old composition), so a second run can go beside the first. There is no cancel
  button, and `progress` is never passed, so nothing moves on the screen.

How shown: by reading, and by `ReviewerFaucetTest` in my clone (`app/src/test/.../ReviewerFaucetTest.kt`,
not part of the suite). A "faucet" on 127.0.0.1 that sends its 95-byte answer a byte a second held one
question of `Faucet.ask` for 94 s (connect limit 15 s, read limit 30 s). A `solve` for 24 bits whose
coroutine was cancelled after 50 ms ran on to its end (1.3 s, 20.6 million tries). On this computer `solve`
does about 16 million hashes a second (a desktop JVM): 2^24 in about a second, the most, 2^28, in about 17 s.
A phone is slower by a factor I did not measure, so on a phone an honest faucet's work is likely the
promised seconds, and the most a few minutes of one core per press: bounded, but not stoppable. A random
challenge of 24 bits costs 2^24 on average; the full 2^28 takes a challenge without an answer below it,
which a hostile faucet would have to search for once (about one in nine million challenges) and could then
hand out again and again, because the phone does not look at the challenge's form.

What would put it right: a deadline for each question (60 s, as `faucet.py` uses for its own asker), `until` capped at now + 600 s (the faucet's own
`CHALLENGE_LIFE`), a cancellation check in the loop (`ensureActive()` every 65,536 tries, where the clock is
already read), the job held by the `WalletModel` so that it is neither doubled nor orphaned by leaving the
page, the network part on `Dispatchers.IO`, and a cancel button or the progress that `solve` can already
report.

### L2 (low). The setup screen calls every typed server Northbound's, Polarka's own included

Places: `MainActivity.kt` 204-217; `server_intro` in `res/values/strings.xml` and `res/values-cs/strings.xml`.

The intro text is chosen by `serverUrl == Setup.POLARKA_SERVER` on the raw text of the field. Anything else
gets `server_intro`: "The server filled in below is run by the Northbound project, not by the authors of
this app." That is said of a third server typed in, of `https://node.polarka.org/` with a slash, and of
`node.polarka.org` typed without the scheme, which `Setup.server` turns into `https://node.polarka.org`, that
is into Polarka's own server, while the screen has just said it is Northbound's. The check-before-saving
screen then shows the address and `server_sees`, and does not say whose the server is. 0.6.1 showed the same
sentence for a typed address; 0.7.0 is the version that names owners, and the brief's question "is it clear
whose each server is" gets a wrong answer for a typed one.

How shown: by reading (no test renders the chips or the intro). What would put it right: three texts, the
Northbound one only for exactly `DEFAULT_SERVER`, the Polarka one only for exactly `POLARKA_SERVER`, both
compared after the normalisation of `Setup.server`, and for anything else a neutral one ("this server is run
by whoever runs it; neither Northbound nor the authors of this app vouch for it"); and the owner named again
on the check-before-saving screen.

### L3 (low). The fixed address: what it gives away is said in part, not where it is switched on, and the way back is named wrongly

Places: `receive_note_fixed`, `fixed_address_setting` (en and cs); `Tabs.kt` 276-290 and 583.

- The switch in More ("One fixed receiving address (turn off for a new address for each payment)") says
  nothing of privacy. The note on the receive page says "Whoever knows it can see every payment to it". More
  is given up: every payer sees what all the others paid and the total; and when coins of the fixed address
  are spent, the spending transaction and its change are linked to it, and through them the other addresses
  whose coins are spent beside it. The fixed address becomes a handle on the wallet as a whole, not only on
  the payments to it. A wallet server sees all addresses together anyway, so the loss is towards payers and
  anyone they tell.
- "turn this off in Settings" (cs "v Nastavení"): the app has no Settings. The switch is on the More tab, in
  the block "Wallet in this phone" (cs "Více", "Peněženka v tomto telefonu"). The commit message says
  "Settings" too.
- After the fixed address is switched off, the "new" address shown is the one at `receiveIndex`. When no
  payment has come to the fixed address yet, that is address 0, the fixed address itself, now shown as
  "Address number 1" for a new payer.

How shown: by reading. What would put it right: one sentence at the switch and on the receive page that
names the linking of payers and of the wallet's later payments; "under More, Wallet in this phone"; and on
switching it off, move `receiveIndex` past 0 if address 0 was ever shown as fixed (or show the next unused
key after it).

### L4 (low). The README still describes 0.6.1

Place: `README.md` at 6dc0f38, lines 31-36 ("Getting the package"), 60 (receiving), 72-82 ("Where the wallet
asks").

The two commits under review do not touch the README. Its changes since the tag (the 0.6.1 package's
checksum, a test by hand on a phone) come from two commits of 0.6.1 documents that lie between the tag and
them (67ec454, 0a22619; the tag `v0.6.1` names 4c0f648; I checked that those two touch no code). Nothing in it
says that there are two editions and which one is published, that the first screen now also offers
Polarka's own server at `node.polarka.org` (which, when chosen, sees the wallet's addresses together and the
phone's internet address, like any wallet server), that a fixed receiving address can be chosen and what it
costs, or that the personal edition sends its address to `faucet.polarka.org`. "The first screen offers the
public server of the Northbound project ... It is named there at its author's request and is not run by this
project" is now half of what the screen does. The changed lines that are there are true; what is missing is
the change itself. The release notes were not part of what I was given.

What would put it right: the README brought to 0.7.0 before the public package goes out (text only).

### L5 (low). Nothing in the suite tests what the editions do, and two test names claim more than they test

Places: `Version07Test.kt`; the screen tests in `app/src/test`.

`Version07Test` holds three tests: the new field of the wallet file goes round (written only when set; the
edition decides when it is not); five counts of zero bits; and an answer of `solve` checked with the same
Kotlin counter. No test renders the receive page with a fixed address or after "Next address", none looks
for the absence of the faucet in the public edition or on regtest, none touches the server chips, the
intro texts, the switch in More or `Faucet.ask` (its bound on bits, its reading of errors, its limits of
size and time). `next-address`, `receive-note`, `fixed-address-setting`, `server-pick-*` and `faucet-*`
appear in no test. The 141 tests pass in both editions largely because the old ones do not look where the
editions differ: `ScreenTest` checks the first address of a new wallet, which is address 0 in both.
`zeroBitsAreCountedAsTheFaucetCountsThem` and `theAnswerFoundIsOneTheFaucetTakes` compare the Kotlin code
with itself; `faucet.py` is not run, and the challenge in the test (`abc123|16|1795209999|nonce`) is not of
the faucet's form (`bits.expires.nonce.mark`). By reading I found the two counters and the hashed message the
same (below), so the names are not wrong in substance, but they are not shown.

How shown: by reading and `grep` of `app/src/test`, and by a fault of my own: with `Tabs.kt` 276 made to show
address 0 in every edition, `ScreenTest` (the only test that opens the receive page) and `Version07Test` all
pass in the public edition ("Tests I ran", 5). I checked the public package's behaviour from its dex instead
(below). What would put it right: one screen test for each edition (public: a new address after a
payment and after "Next address", none with the switch on, no faucet; personal: address 0, no "Next
address"), and a test of `Faucet.ask` against a local stand-in that answers as `faucet.py` does.

### N1 (note). The two editions are one app to Android

Both packages are `org.polarka.wallet`, version code 13, signed with the same key, so each installs over the
other in place and keeps the wallet. That is what the owner's phone needs (0.6.1 public to 0.7.0 personal
keeps the wallet), and it is also what makes a mix-up silent: if the personal file were put where the public
one belongs, every phone that takes it would show a fixed address by default and offer the faucet, and an
update tool could not tell the two apart. The only visible difference is the version name `0.7.0-personal`
(in the banner, `Parts.kt` 154, and in About, `Tabs.kt` 645). I would add to the release steps a check of the file to be published, as done
here: `BuildConfig.FLAVOR` in the dex is `"public"` and `FAUCET`/`FIXED_ADDRESS` are `false`.

### N2 (note). The public package carries the faucet's code, address and texts, unreachable

`isMinifyEnabled = false`, and `Faucet.kt`, `FaucetBlock` and the faucet strings are in the main source set,
so the public package holds `Faucet` with `https://faucet.polarka.org` and all six faucet strings
(`resources.arsc` is the same file in both packages). Only the call is gone: in the public dex `ReceiveTab`
does not call `FaucetBlock`, and nothing else does. Harmless today; whoever reads the public package finds
a faucet that the commit says it does not have, and a later change could make it reachable. A `personal`
source set for these files would leave them out of the public package.

### N3 (note). The faucet's texts say more than the faucet's answer

- "The faucet sent it" is the faucet's word; `faucet.py`'s own asker says "this is the faucet's word; the
  coin shows in the balance once it is in a block". The balance itself cannot be faked by the faucet: it
  comes only from the wallet's server or node.
- `NOT_NOW` is shown as "The faucet said no (NOT_NOW). Try again later." `faucet.py` uses it also for "the
  payment was made but its outcome is not known yet; do not ask again for this address today".
- The phone's own refusals are shown as the faucet's: more than 24 bits asked, or a challenge that has run
  out by the phone's clock (a phone more than ten minutes ahead of the faucet gets this every time), both
  read "The faucet said no (WORK)".
- "about two minutes" is the usual wait for a block, not a bound.

### N4 (note). Small things in the diff

- `WalletModel.kt` 672-674: the comment "Switching the lock off is something only the owner may do." now
  stands above `saveFixedAddress` (two KDoc comments in a row), not above `saveLock`.
- `Tabs.kt` 690: the block for emptying a paper address sends to the address at `receiveIndex` and shows it,
  while the receive page with a fixed address shows address 0. Both are the wallet's; only the screens
  disagree.
- The commit message says that the personal receive page "shows the first address always"; the owner can
  switch that off in More, as in the public edition.
- Every commit of the clone, the two under review included, has the author address
  `356183614+severka-monitor@users.noreply.github.com`. No file links to `severka-monitor`, but GitHub ties
  commits with that address to the suspended account when they are pushed.

### N5 (note). Where walletd, the server behind node.polarka.org, differs from the real server

- `pending_revision` starts again at 1 at every start of walletd (`Index.revision = 0`, raised at the first
  look, `walletd.py` 439 and 500-503). A cursor handed out before a restart is taken after it when the tip
  and the number happen to agree, though the waiting transactions may not; the client sees the same height,
  hash and revision on every page and puts the pages together. It needs a restart in the middle of one
  reading (walletd reads the whole chain before it listens again, so such a reading usually fails first) and
  the same number on both sides: rare. A random number drawn at start and carried in the revision or in the
  cursor would close it.
- The time a waiting transaction was first seen is kept in memory only and starts again with walletd. The
  wallet judges nothing from it.
- One request at a time (one lock around each answer, `App.handle`, 535-537), each answer a fresh look at the node
  with at least four calls, and, with walletd installed as one file as `deploy/walletd/README.md` says, one
  `validateaddress` call for every address named. No rate limit (the real server answers 429 above 100 a
  second), and on the public instance at most 12 connections, past which a connection is closed without an
  answer. Anyone can make `node.polarka.org` slow or unreachable. The wallet then shows an error and judges
  nothing, so it fails safe, but the Polarka choice is easier to deny than Northbound's.
- walletd answers 503 to all wallet data, and to `/ready`, while its node reports `initialblockdownload`
  (`node_ready`, line 568). On main that is the node catching up after a start; fails safe. On a fresh
  regtest node it means "not ready" until a block is mined, and the wallet's
  test support (`ServerSupport.startReal`) waits for `/ready` before any block is mined: walletd cannot be put
  under the wallet's suite through `NORTHBOUND_SERVER` as it is. My first run hung in that wait
  (`logs/walletd1.log`, stopped by me). So, as far as the repository shows, the wallet's own tests have never
  stood on the server that 0.7.0 now offers; they stand on `review/standin/northbound_standin.py`, an older sibling of walletd (687 lines
  against 871). For my second run I changed that one line in my copy (`tools/walletd-copy/walletd-regtest.py`;
  on regtest the IBD flag is not counted).

With that one line changed, the wallet's `ServerEngineTest` and `ServerPayTest` (the two classes the author
ran against Northbound's server on 18 November) stood on walletd: 23 tests, 22 passed, 1 skipped (it needs a
switch of the stand-in), none failed; walletd logged no fault, only three 502s for broadcasts that the node
refused, as those tests expect (`logs/walletd2.log`, `logs/walletd2-totals.txt`,
`logs/walletd2-server-logs.txt`). Apart from the readiness rule, then, walletd answers the wallet's engine
as the stand-in does. I did not run the screen and model classes over a server on walletd.

## What I checked and found sound

**The packages** (`apksigner`, `aapt2`, `dexdump` of build-tools 36.0.0; my tools `tools/apk-entries.py`,
`tools/dex-classdiff.py`, outputs in `logs/apk/`):

- The three files have the SHA-256 the brief gives. Each verifies (scheme v2, which is enough for minimum SDK
  31) with one signer whose certificate has SHA-256 `f441275239c880d0855a40525ca2449a2aa4992b4d4c2e63143d36c088ce103b`,
  the same as 0.6.1. `META-INF/version-control-info.textproto` names commit `6dc0f3838ee0...` in both 0.7.0
  packages.
- The manifests are those of 0.6.1 but for version code and name and one resource number (the label's id
  moved by one because strings were added): the same seven permissions and one of the app's own,
  `allowBackup` false, `usesCleartextTraffic` false, only the launcher activity exported, with no intent
  filter that takes data, `WatchJob` not exported.
- Public against personal: three entries differ (`AndroidManifest.xml`, `assets/dexopt/baseline.prof`,
  `classes3.dex`). In the dex, once Kotlin's internal names (which carry the edition) are set aside, three
  classes differ and no more: `BuildConfig` (`FLAVOR` "public"/"personal", `FIXED_ADDRESS` and `FAUCET`
  false/true, `BUILD_TYPE` release, `DEBUG` false), `WalletInfo.getFixed` (the constant 0/1), and `TabsKt`,
  where only the personal `ReceiveTab` calls `FaucetBlock`. `resources.arsc` is the same file.
- 0.6.1 against public 0.7.0: `classes.dex` and `classes2.dex` differ only in resource numbers (constants
  `0x7f0d....` moved by the new strings); in `classes3.dex` only classes of `org.polarka.wallet` differ, none
  of `core`, `node` or a library. That matches the source diff, which touches no code outside `app`.
- URLs in the public dex: `api.northboundwallet.org`, `node.polarka.org`, `polarka.org`, and the unreachable
  `faucet.polarka.org` (N2). No `github` and no `severka` in the dex of either edition or in the resources.

**The editions.** Nothing can switch a public package to the personal behaviour. `FAUCET` and `FIXED_ADDRESS`
are constants the compiler put into the code. The one input read at run time is `WalletInfo.fixedAddress`,
written only by the switch in More, kept in the sealed wallet file (AES-GCM under a Keystore key, label
`north-wallet-info/1`), null after a restore from the words, and absent from setup files. No intent, link or
extra is read; no other component is exported; backups are off. With the switch off the public receive page
shows a new address for each payment as 0.6.1 did; Northbound's server is filled in as before.

**The fixed address.** It changes what is shown and nothing else. `Engine.allowed` and `ServerEngine.allowed`
still look at `max(AUTO, lookTo, max(receiveIndex, changeIndex) + 1)` keys of both branches, and address 0 is
always among them; `settle` still moves `receiveIndex` up as addresses are used; change, the choice of coins,
the lists of waiting and dropped payments and `judge` are untouched (the safeguard of the sixth look rests on
those, and the diff does not reach them); notifications go by height and history, not by the address shown.
A wallet brought back from its words finds payments to address 0 first. A file of 0.6.1 opens in 0.7.0
(`fixedAddress` absent, so the edition decides); a file of 0.7.0 opens in 0.6.1, whose `parse` reads fields
by name and passes over the new one, and the format and sealing label are unchanged. The owner's choice is
lost only by a round trip through 0.6.1, which writes the file without it.

**The choice of server.** Both offered addresses are https, and `Setup.checkedUrl` refuses any other scheme,
user info, path, query or fragment; `ServerClient` follows no redirect, so one server cannot hand the wallet
to the other; no code changes a stored setup from one server to the other; a chip only puts its address into
the visible field. For those two exact addresses the texts say whose the server is, and `server_sees` (what
any server sees and could do) is shown for both. Nothing is pinned beyond the phone's certificate
authorities, as in 0.6.1.

**The faucet** (`Faucet.kt` against `faucet.py`). The phone sends `POST /v1/challenge {"address"}` and
`POST /v1/claim {"address","challenge","answer"}` to `https://faucet.polarka.org`, an address fixed in the
code, with the default headers of the phone's HTTP stack (its User-Agent names the phone's model, as for every
server the app asks) from the phone's internet address. That is what `faucet.py` needs (the address to pay,
the place to count), and `faucet.py` keeps no internet address on disk. The address is the one shown, so
with a fixed address it is address 0. Answers are read up to 64 KiB plus one buffer, and with Android's
transparent gzip the bound holds on what is unpacked; anything unreadable is "UNREACHABLE"; the error word
is passed on only from the list of `faucet.py`'s words, so a faucet cannot put text of its own on the screen;
the transaction id must be 64 lowercase hex characters; redirects are not followed; a coin cannot be shown in
the balance by the faucet. The work is the same on both sides: SHA-256 of `challenge + ":" + answer`, zero
bits counted from the first byte alike (`Integer.numberOfLeadingZeros(v) - 24` is `8 - v.bit_length()`), the
answer lowercase hex without leading zeros (at most 7 characters below 2^28, which `[0-9a-f]{1,32}` takes),
`expires` in seconds on both sides, and 24 bits as the most, as `faucet.py`'s `ASK_BITS` (its own `bits()`
asks no more unless its owner sets the base above 24). Memory is bounded; work and time are not as they
should be (L1).

**The texts.** The twelve new strings are in both languages and say the same in both. Apart from what L2,
L3 and N3 name, they say what the code does: the Polarka server is run by the app's authors on their own node
(`deploy/walletd/README.md` and `node.caddy` of polarka-agent say so: a walletd of its own behind
`node.polarka.org`, separate from the pool's, Caddy's log discarded); the faucet pays an address once a day at
most (`faucet.py`: one address once in 24 hours, a place twice); the link in More goes to
`https://polarka.org`. No link in the tree, in the strings or in the packages leads to `severka-monitor`. The
changed lines of the README are true (see L4 for what is missing).

**`walletd.py` against what the client expects** (`docs/northbound-api.md`, `ServerClient`): coins ordered by
height, id and number, history with the waiting transactions first, then height downwards, then id;
cursors bound to the tip hash and the revision, 409 when either changed, never for a first page; limits as
the real server (coins 1 to 10,000 a page, 1,000 when none is named; history 1 to 200, 100 by default; 100
addresses; 1 MB, and Caddy's 256 KB in front of the public instance is above the largest transaction the
planner makes, 1,000 inputs, about 214 KB of hex); `net_amount` as received less own inputs, `network_fee` the
fee of the whole transaction and null only for a block reward; a waiting line with no height, 0
confirmations and the time it was first seen; the index fields the client checks (`ready`,
`pending_available`, indexed equal to observed, both from the one picture); 503 while the node catches up and,
on main, without a peer or with a tip older than two hours; broadcast errors as 502; `known` from the picture
or the node. One extra field (`coinbase` in history lines) the client passes over. Every answer comes from one
picture taken under one lock, so coins, activity and history of one answer belong together.

## Tests I ran

All through `tools/gradle-bg.sh`, one at a time, offline; logs in `logs/`. Totals by `tools/junit-sum.py`
(a variant of `junit-show.py` that reads one edition's results folder).

1. `:app:testPublicDebugUnitTest` at 6dc0f38 (`logs/public1.log`, `logs/public1-totals.txt`): 141 tests, no
   failure, no error, 1 skipped (`PublicNodeTest`, which needs a public node). 09:04 to 09:16.
2. `:app:testPersonalDebugUnitTest` (`logs/personal1.log`, `logs/personal1-totals.txt`): 141 tests, no
   failure, no error, the same 1 skipped. 09:17 to 09:27. The author's "141 in each edition, none failed"
   is right; the skipped one is not mentioned.
   `Version07Test`'s three tests pass in both.

3. The server tests on walletd (`tools/walletd-as-northbound.sh`, then `-regtest.sh`; `NORTHBOUND_SERVER`,
   `NORTHBOUND_NO_WAIT` because walletd answers every question from a fresh look at its node): the first run
   (`logs/walletd1.log`) waited 90 s at every start of a server, because walletd is not ready on a fresh
   regtest node (N5); I stopped it after 16 minutes by the numbers of its processes. The second
   (`logs/walletd2.log`, 09:45 to 09:50), with the one line changed: `ServerEngineTest` 16 of 16,
   `ServerPayTest` 6 of 7 and 1 skipped.
4. `ReviewerFaucetTest`, my three tests of `Faucet` (in the same run): the trickle (94 s for one question),
   the cancelled coroutine (the work ran to its end), the rate (about 16 million hashes a second here). See L1.
5. A fault of my own: `Tabs.kt` 276 changed so that every edition always shows address 0 (the public
   edition's promise of a new address for each payment broken), then `ScreenTest` and `Version07Test` in the
   public edition (`logs/mutant-fixed.log`, `logs/mutant-fixed-totals.txt`): all 6 passed. No other test opens
   the receive page, so the suite cannot catch it (L5). The source is put back; `git status` shows only my
   untracked `ReviewerFaucetTest.kt`.

My runs left in the system's temporary folder (`/var/folders/9n/.../T/`) 33 folders
`polarka-real-server-*` of a few KB each (walletd's logs: the first run's, which the test support does not
remove when a server never gets ready, and the second run's, which I asked it to keep) and one
`polarka-server-node-17354123355185894015` (16 MB, the regtest node of the stopped run). The rules let me
write only in my folder, so I did not remove them; they can be deleted.

`:core:test` and `:node:test` I did not run: the diff does not touch those modules and their classes are
the same in the 0.6.1 and 0.7.0 packages.

## What I did not check

- Anything on a phone or an emulator: how the new screens look, the faucet's work on a phone's processor
  (my rate is a desktop JVM's), the link to the browser.
- The real servers. `api.northboundwallet.org`, `node.polarka.org`, `faucet.polarka.org` and `polarka.org`
  were not contacted, so I do not know that `node.polarka.org` runs this walletd behind this Caddy file, or
  what `polarka.org` says about the app.
- Whether the packages were built from these sources. No Release task was run (the brief forbids it); what
  ties the files to 6dc0f38 is `version-control-info.textproto` and the agreement of their dex with the
  source diff, not a rebuild.
- `faucet.py` beyond what the phone meets (its limits per place and per hour, its payments, its journal) and
  `walletd.py` as a public service (its exposure to load, the node's RPC whitelist for its user, deep
  reorganisations, a large mempool on main). Both were read, not reviewed as services.
- Code that the diff does not change (the safeguards of the sixth look, the planner, signing, `deliver`),
  other than to see that the fixed address does not reach it; the 0.6.1 documents in `review/` that the
  commits between the tag and the change brought (I only searched them for links).
- The release notes and the Releases page, which were not given to me.

