package org.polarka.wallet

import north.core.Plan
import north.core.SignedSpend
import north.node.ServerClient

/**
 * What the app asks of whatever stands between it and the chain. Two things can stand there: a node, the
 * owner's own or a public one ([Engine]), and a wallet server ([ServerEngine]). The model behind the screens
 * and the notifications know only this, so that they do the same with either.
 *
 * What each of the two can and cannot tell is written at its class. Nothing here keeps a secret: [sign] and
 * [paperSign] are handed the words for the moment of signing and do not keep them.
 */
interface Gateway {
    /**
     * True when what is asked is a node of the owner's own (a setup with an account). What such a node refuses
     * it does not pass on, so its no to a transaction is the end of that transaction. Anybody else who was
     * shown a signed transaction, a public node or a wallet server, may keep it and send it later, whatever
     * they answered. A payment that such a party refused is therefore not forgotten: it is remembered as one
     * that did not go through, and the next payment is built on one of its coins.
     */
    val ownersNode: Boolean

    /** How many keys of each branch the app may look at for this wallet without asking its owner again. */
    fun allowed(wallet: WalletInfo): Int

    /** How many keys of each branch a look at this wallet covers. */
    fun windowOf(wallet: WalletInfo): Int

    /** One look at the chain for the wallet and the watched addresses. */
    fun fetch(wallet: WalletInfo?): Snapshot

    /**
     * What arrived in blocks after [afterHeight]: rewards and payments, not the change of the wallet's own
     * payments. Comes with the height that the answer reaches to.
     */
    fun incomingSince(wallet: WalletInfo?, afterHeight: Int): Pair<Int, List<Incoming>>

    /**
     * A payment from the wallet, worked out from a fresh look at its coins. [amountSat] null means everything.
     * [used] are coins the app knows to be tied up by its waiting payments.
     */
    fun plan(wallet: WalletInfo, to: String, amountSat: Long?, feeRate: Long, used: Set<String> = emptySet()): Plan

    /**
     * Signs with the given words, which have to be those of the wallet with [expectedXpub]. Nothing is sent
     * here, and nobody but the owner's own node is shown the result ([ownersNode]).
     */
    fun sign(expectedXpub: String, plan: Plan, words: List<String>): SignedSpend

    /**
     * Hands a signed transaction over. True: it is on its way. False: what became of it could not be found
     * out. A refusal is thrown. Unless [ownersNode] is true, whoever refused has seen the whole transaction
     * by then, and the payment must not be forgotten because of the refusal.
     */
    fun deliver(signed: SignedSpend): Boolean

    /**
     * Looks at what the account of some recovery words holds (a paper address). The words are not kept.
     * [spent] are coins already sent away in an earlier part of the same emptying.
     */
    fun paperLook(words: List<String>, spent: Set<String> = emptySet()): PaperFound

    /**
     * Empties a paper address. With [keepSat] null everything goes to [to]. Otherwise [keepSat] goes to [to]
     * and the rest to [restTo].
     */
    fun paperPlan(found: PaperFound, to: String, feeRate: Long, keepSat: Long? = null, restTo: String? = null): Plan

    /** Signs the emptying of a paper address with its own words. Nothing is sent here. */
    fun paperSign(found: PaperFound, plan: Plan, words: List<String>): SignedSpend

    companion object {
        /** What stands between the app and the chain for this setup: the wallet server it names, or its node. */
        fun of(setup: Setup): Gateway =
            if (setup.server) ServerEngine(setup.chain, setup.addresses, ServerClient(setup.url, Engine.TIMEOUT_MILLIS))
            else Engine(setup)
    }
}
