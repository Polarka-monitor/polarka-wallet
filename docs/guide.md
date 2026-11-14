# Polarka Wallet: a short guide

Polarka Wallet is a test build. It has been checked twice, but not yet by an independent human expert.
Keep only small amounts in it. Larger savings belong on paper (a cold address).

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

## First start: the node

The wallet has no server of its own. It asks a TrueNorth node what your addresses hold.

- **A public node** is the quick way: confirm the address that is offered, or type another one. A public node
  sees your addresses and your internet address, and it could show a false balance or hold a payment back.
  It cannot take your coins.
- **A node of your own** keeps your addresses to yourself. See the last chapter.

Run the self-test once (first screen, or More). It shows that the key code works on your phone.

## Making a wallet

1. Overview, "Make a new wallet". The phone shows 24 words.
2. Write them on paper, in order, by hand. Do not photograph them, do not type them anywhere, do not store them
   in any app. Whoever has the words has the coins. Without them, a lost or broken phone means lost coins.
3. The app asks three of the words back, then seals the words with your fingerprint or screen lock.
4. Keep the paper where fire, water and curious people do not reach it.

If you remove the screen lock of the phone, Android destroys the key that protects the words. The app then
tells you so; the paper is what brings the wallet back.

## Receiving

Receive shows an address and its QR code. Give it to the payer. A payment appears after its first block,
usually within a few minutes; before that the wallet cannot see it. After an address has been used the wallet
offers a fresh one; old addresses keep working.

## Sending

1. Send: paste the address or scan its QR code, type the amount (or tick "Send everything"), pick a fee rate.
2. "Check the payment" shows the address, the amount, the fee and what returns to you. Compare the address
   with its source character by character.
3. "Sign with my fingerprint and send". A payment that has been sent cannot be taken back.

Until its block arrives the payment is listed on the Overview as waiting, and its coins are not offered again.
If the node does not confirm that it took the payment, the app says that the outcome is not known. Do not pay
again then: refresh after a minute, and the app shows whether the payment waits for a block or did not go
through.

## Bringing a wallet back

More, "Bring back a wallet": type the 12 or 24 words, separated by spaces. The coins are found from the chain,
however long the wallet had been in use. Do this once with a small amount before you rely on it: it is the
only test that your paper is right.

By itself the app looks at the first 200 addresses of the wallet, 40 with a public node. If the node shows
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
fingerprint when the app opens"). Removing the wallet from the phone destroys the stored words; the coins stay
on the chain and are reached with the words on paper. Uninstalling the app or clearing its data does the same.

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
