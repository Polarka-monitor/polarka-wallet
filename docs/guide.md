# Polarka Wallet: a short guide

Polarka Wallet is a test build. It has been checked by its author, with the help of AI, by an independent AI
reviewer, and once, at version 0.7.1, by people outside this project. What they found is put right in 0.7.2;
nobody independent has looked at those fixes yet.
As with any wallet in a phone, savings that you do not need at hand are safest on paper (a cold address).

## Before you install

- An Android phone with Android 12 or newer, with a screen lock. A fingerprint makes it comfortable.
- The package (`.apk`) from the project's page, and the two fingerprints published next to it.
- Check the package before you install it. On a computer:

      sha256sum polarka-wallet-X.Y.Z.apk
      apksigner verify --print-certs polarka-wallet-X.Y.Z.apk

  The first number must equal the published SHA-256 of that release. The certificate's SHA-256 must be
  `f441275239c880d0855a40525ca2449a2aa4992b4d4c2e63143d36c088ce103b` for every release. If either differs,
  do not install the file.
- Android asks for permission to install from the app you opened the file with. Allow it for that once.

## First start: a server, or a node of your own

The wallet asks a server or a node what your addresses hold, and which one is your choice.

- **A public server** is the quickest way and is offered first. Two are offered. The one filled in is the
  server of the Northbound project (`https://api.northboundwallet.org`); it is not run by the authors of this
  app. The other is Polarka's own (`https://node.polarka.org`), run by the authors of this app on a node of
  their own. The screen says whose the server in the field is, and once more before it is saved; of an
  address you type yourself it says that the app does not know who runs it. Any of them sees all the
  addresses of your wallet together, every address you watch or empty through the app, and your internet
  address. It could show a false balance, keep an incoming payment from you, hold a payment back, or call a
  payment done that is not. It cannot take your coins: the words and the keys never leave the phone. While
  the server is down the wallet shows nothing; the coins stay yours and you connect elsewhere. The address
  of another server with the same interface can be typed in place of the one filled in.
- **A node of your own** keeps your addresses to yourself. See the last chapter.

What is different through a server:

- A payment that waits for a block shows in the balance at once: the coin it is paid from is not counted,
  and the change from it is shown apart as an amount on its way.
- For a wallet with more than a hundred addresses (after about fifty payments) the history may lack the fee
  of some payments and, rarely, a whole line. The History page says so. The balance and the coins are
  always complete.
- The mining page does not show the hash rate of the network, and a block reward is known as one only
  until it is spent.
- A payment that the server refuses is not forgotten. The server has seen the whole payment by then and
  could still send it, so the app keeps it on the Overview as one that did not go through and builds your
  next payment on one of its coins.
- An entry "Did not go through, could still arrive" goes when the server shows that payment in a block, or
  a later payment of yours that was built on one of its coins. A server does not tell what else has spent a
  coin. If the coin went another way (from another phone with the same words), or if the server leaves the
  coin out, the entry stays and the app makes no payment until you forget the entry yourself. Before you do,
  look at the wallet through another server or a node of your own.

Run the self-test once (first screen, or More). It shows that the key code works on your phone.

## Making a wallet

1. Overview: choose 24 or 12 words and "Make a new wallet". 24 words carry 256 bits of randomness, 12 words
   128 bits; twelve words are easier to write down, and the Northbound wallet for computers opens them too.
   The number of words of a finished wallet cannot be changed. The phone shows the words.
2. Write them on paper, in order, by hand. Do not photograph them, do not type them anywhere, do not store them
   in any app. Whoever has the words has the coins. Without them, a lost or broken phone means lost coins.
3. The app asks three of the words back, then seals the words with your fingerprint or screen lock.
4. Check the paper in full before you send coins to the wallet: More, "Check the whole paper".
   Type all the words from the paper. The app says whether they are the words of this wallet. Three words
   asked back cannot show that the others are right; this check can. The app never shows the words
   again, so a mistake found later can only be mended by moving the coins to a new wallet.
5. Keep the paper where fire, water and curious people do not reach it.

If you remove the screen lock of the phone, Android destroys the key that protects the words. The app then
tells you so; the paper is what brings the wallet back.

## Receiving

Receive shows an address and its QR code. Give it to the payer. A payment appears after its first block,
usually within a few minutes; before that the wallet cannot see it. After an address has been used the wallet
offers a fresh one; old addresses keep working. A new address for each payer keeps your payments apart.

If you would rather give everybody the same address, tick "One fixed receiving address" under More, Wallet
in this phone. Receive then always shows the wallet's first address. That costs privacy: every payer can see
what all the others paid to it, and the payments you make with those coins can be linked to it. Untick it
to have a new address for each payment again; the address shown next is then a new one, not the fixed one.

## Sending

1. Send: paste the address or scan its QR code, type the amount (or tick "Send everything"), pick a fee rate.
2. "Check the payment" shows the address, the amount, the fee and what returns to you. Compare the address
   with its source character by character.
3. "Sign with my fingerprint and send". A payment that has been sent cannot be taken back.

Until its block arrives the payment is listed on the Overview as waiting, and its coins are not offered again.
If the node or the server does not confirm that it took the payment, the app says that the outcome is not known. Do not pay
again then: refresh after a minute, and the app shows whether the payment waits for a block or did not go
through.

A payment that waits for a block can still arrive, and a payment made meanwhile does not stand against it: if
you pay the same thing a second time while the first one waits, both can arrive. When a payment has waited for
over an hour the app says so, on the Overview and where the next payment is confirmed. Find out first what
became of it. A server or a public node can hold a payment back, so look at the wallet through another server
or a node of your own before you pay again.

A payment that did not go through stays on the Overview as "Did not go through, could still arrive": the node
or the server that the app asks does not hold it, but another node might. The same is done with a payment
that a server or a public node refused, because they have seen it. You can pay again at once. The app builds
the new payment on a coin of the old one, so that only one of the two can ever arrive, and it removes the
entry when a block has settled the matter. "Forget this payment" is for the rare case that the entry stays
and you are sure the old payment can no longer arrive.

## Bringing a wallet back

More, "Bring back a wallet": type the 12 or 24 words, separated by spaces. The coins are found from the chain,
however long the wallet had been in use.

If the words are not valid, the app says what is wrong and keeps what you typed, so that you can put it right:
a word that is not a recovery word is named with its place ("5 (wordx)" is the fifth word), a wrong number of
words is counted, and words that are all on the list but do not fit each other mean that at least one of
them is not the right word, or that the order is not right. If you typed exactly what the paper says, the
paper was copied wrongly. What you typed goes when you leave the page, and when you come back to the app
after more than a minute away.

Rarely a wrong word still gives valid words: of 24 words, about one wrong word in 256 does, of 12 words one
in 16. The app then brings back another, empty wallet. If a wallet that should hold coins comes back empty,
look at the paper again before anything else.

A paper that has never been tried is a hope, not a backup. Check it while the wallet is still in the phone
(More, "Check the whole paper"), or bring the wallet back from it once with a small amount.

By itself the app looks at the first 200 addresses of the wallet, 40 with a public node or a server. If the node shows
more of them in use, the Overview asks whether the app may look further. Say yes if you have used the wallet
that much. If you have not, the node is showing something that is not true: say no and use another node. If
coins that you know of are missing after a wallet was brought back, More has "Look at twice as many".

## Emptying a paper address

More, "Empty a paper address": type the words of the paper address. The app shows what it holds. Move
everything, to this wallet or partly to a new paper address: a paper whose words have been typed into a phone
is no longer cold. Block rewards younger than 100 blocks cannot be moved yet; the app says how much stays and
for how long, and then the paper must be kept and emptied once more. Never type the words of a paper address
that holds more than you would carry in a phone.

## Lock and removal

The app locks after a minute in the background and asks for the fingerprint again (More, "Ask for the
fingerprint when the app opens"). The lock in the top bar locks it at once; the phone then asks for the
fingerprint only after a tap on Unlock. Removing the wallet from the phone destroys the stored words; the
coins stay on the chain and are reached with the words on paper. Uninstalling the app or clearing its data
does the same.

## A node of your own

On the computer with TrueNorth Core (v30.5.4 or newer), add to `truenorth.conf`:

    server=1
    blockfilterindex=1
    rpcauth=phone:<made with rpcauth.py, as for Bitcoin Core>
    rpcwhitelist=phone:getblockcount,getblockchaininfo,getbestblockhash,getblockhash,getblockheader,scantxoutset,estimatesmartfee,testmempoolaccept,sendrawtransaction,getrawtransaction,gettxout,getmempoolentry,scanblocks,getblock,getnetworkhashps
    rpcwhitelistdefault=0

That user can read the chain and pass on finished transactions and nothing else. The phone needs https with a
certificate it trusts: Tailscale Serve in front of the RPC port is the simplest way (the node is then reachable
only from your own devices); a web server with a certificate works as well. Then make the setup file and send
it to the phone:

    {"format":"polarka-setup/2","node":{"url":"https://your-node.example","user":"phone","password":"..."},"chain":"main","addresses":[]}

`addresses` may list up to twenty addresses to watch without their keys, for example the address you mine to.
Import the file on the first screen ("Choose the setup file"). The password is not shown, is stored encrypted,
and the app offers to delete the file afterwards.
