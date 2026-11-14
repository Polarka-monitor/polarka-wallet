package org.polarka.wallet

import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import north.core.Account
import north.core.Chain
import north.core.Plan
import north.core.Planner
import north.core.SelfCheck
import north.core.Words
import north.node.NodeError
import javax.crypto.Cipher

/** The owner of the phone, as far as the app can ask for them. The screen provides it; a test lets everything through. */
interface Owner {
    /** Asks the owner to unlock this cipher with a fingerprint or the screen lock. Throws if they do not. */
    suspend fun unlock(cipher: Cipher): Cipher

    /** Asks the owner to show that it is them, with nothing to unlock. */
    suspend fun present(): Boolean
}

class OwnerRefused(val detail: String = "") : Exception("the owner did not unlock")

enum class SelfTest { NOT_RUN, RUNNING, PASSED, FAILED }

/** A short message about something the app has just done. */
enum class Notice {
    FILE_DELETED, FILE_NOT_DELETED, CLIPBOARD_CLEARED, FILE_UNREADABLE, CLIPBOARD_EMPTY, WALLET_READY, WALLET_REMOVED, SAVED,
    PAYMENT_GONE, ADDRESS_LIMIT, WORDS_REMOVED, LOOK_LIMIT,
}

/** Recovery words on their way through the app's state. A printed state or a log line never shows them. */
class SecretWords(val words: List<String>) {
    override fun toString(): String = "<recovery words>"
}

/** A payment worked out and waiting for the owner's yes. */
data class Draft(val to: String, val plan: Plan, val everything: Boolean)

/**
 * The emptying of a paper address, worked out and waiting for the owner's yes. It carries the addresses the
 * plan was made for: the confirmation shows these, never what a text field holds at that moment.
 */
data class PaperDraft(val plan: Plan, val to: String, val restTo: String?)

data class WalletState(
    val loaded: Boolean = false,
    val locked: Boolean = false,
    val lockNote: Boolean = false,              // an attempt to unlock the app did not succeed
    val setup: Setup? = null,
    val offered: Setup? = null,                 // read from a file or the clipboard, not saved yet
    val offerError: SetupError.Reason? = null,
    val offerDetail: String = "",
    val offeredFrom: Uri? = null,
    val notice: Notice? = null,
    val noticeDetail: String = "",
    val wallet: WalletInfo? = null,
    val newWords: SecretWords? = null,          // a wallet being made: shown once, not stored yet
    val walletProblem: Problem? = null,
    val wordsWithoutWallet: Boolean = false,    // recovery words are stored, but the wallet's own file is missing or unreadable
    val busy: Boolean = false,
    val snapshot: Snapshot? = null,
    val problem: Problem? = null,
    val draft: Draft? = null,
    val sending: Boolean = false,
    val sendProblem: Problem? = null,
    val sentTxid: String? = null,
    val paper: PaperFound? = null,
    val paperDraft: PaperDraft? = null,
    val paperBusy: Boolean = false,
    val paperProblem: Problem? = null,
    val paperTxid: String? = null,
    val selfTest: SelfTest = SelfTest.NOT_RUN,
    val selfTestDetail: String = "",
) {
    /** Payments that left this phone and are not in a block yet. They live in the wallet's file. */
    val pending: List<Pending> get() = wallet?.pending ?: emptyList()
}

class WalletModel(app: Application) : AndroidViewModel(app) {
    private val setups: SetupStore = Stores.open(app)
    private val wallets: WalletStore = Stores.wallet(app)
    private val vault: WordVault = Stores.words(app)
    private var engine: Engine? = null
    private var paperWords: List<String>? = null        // only between looking at a paper address and emptying it
    private var paperSpent: Set<String> = emptySet()    // coins sent away in earlier parts of the same emptying
    private var lockWanted = false
    private var lookAgain = false
    private var relooks = 0             // looks the app started by itself, one right after another
    private var doubted: Set<String> = emptySet()       // waiting payments that the last look found nowhere
    private var renumber = false        // the owner asked for the key numbers to be worked out again
    private var sends = 0               // moves on when a payment starts and when it ends
    private val saving = Mutex()
    var io: CoroutineDispatcher = Dispatchers.IO

    /** A waiting payment is not called gone before it is this old: it may still be on its way to the node that is asked. */
    internal var goneAfterMillis = GONE_AFTER_MILLIS

    /**
     * When the app left the front, on the clock that runs since the phone was started and that nobody can set.
     * Kept here and not in the screen: this object survives when Android rebuilds the screen.
     */
    internal var leftAtMillis = 0L

    var state by mutableStateOf(WalletState())
        private set

    init {
        viewModelScope.launch {
            // the three stores are read one by one: a file that cannot be read must not hide the other two
            val setup = withContext(io) { runCatching { setups.load() } }
            val wallet = withContext(io) { runCatching { wallets.load() } }
            val words = withContext(io) { runCatching { vault.exists() }.getOrDefault(false) }
            state = state.copy(
                loaded = true, setup = setup.getOrNull(), wallet = wallet.getOrNull(),
                locked = wallet.getOrNull()?.lockOnOpen == true,
                problem = setup.exceptionOrNull()?.let { Problem(Problem.Kind.STORE, it.javaClass.simpleName) },
                walletProblem = wallet.exceptionOrNull()?.let { Problem(Problem.Kind.WALLET_FILE, it.javaClass.simpleName) },
                wordsWithoutWallet = words && wallet.getOrNull() == null,
            )
            state.setup?.let { engine = Engine(it) }
            if (!state.locked) refresh()
        }
    }

    // ------------------------------------------------------------------------------------------ lock

    fun unlockApp(owner: Owner) {
        viewModelScope.launch {
            if (runCatching { owner.present() }.getOrDefault(false)) {
                state = state.copy(locked = false, lockNote = false)
                refresh()
            } else {
                state = state.copy(lockNote = true)
            }
        }
    }

    /** The app left the front. */
    fun left(now: Long = SystemClock.elapsedRealtime()) {
        leftAtMillis = if (now == 0L) 1L else now
    }

    /** The app is in front again. After more than a minute away it locks; also when the clock seems to have run backwards. */
    fun returned(now: Long = SystemClock.elapsedRealtime()) {
        val away = leftAtMillis != 0L && (now - leftAtMillis > LOCK_AFTER_MILLIS || now < leftAtMillis)
        leftAtMillis = 0L
        if (away) lock()
    }

    /**
     * Called when the app has been in the background for a while. Words of a paper address that are still in
     * memory are dropped in any case. A payment that is being sent at this moment is finished first; the lock
     * follows as soon as it is done.
     */
    fun lock() {
        if (state.sending || state.paperBusy) {
            lockWanted = true
            return
        }
        lockWanted = false
        paperWords = null
        paperSpent = emptySet()
        val wallet = state.wallet
        val locks = wallet != null && wallet.lockOnOpen && state.newWords == null
        state = state.copy(
            locked = locks || state.locked, draft = if (locks) null else state.draft,
            paper = null, paperDraft = null, paperProblem = null,
        )
    }

    private fun lockIfWanted() {
        if (lockWanted) lock()
    }

    // ------------------------------------------------------------------------------------------ node setup

    /** A setup text arrived (from a file or the clipboard). It is only checked here; nothing is stored yet. */
    fun offer(text: String, from: Uri? = null): Boolean {
        state = try {
            state.copy(offered = Setup.parse(text), offeredFrom = from, offerError = null, offerDetail = "", problem = null, notice = null)
        } catch (e: SetupError) {
            state.copy(offered = null, offeredFrom = null, offerError = e.reason, offerDetail = e.detail, notice = null)
        }
        return state.offered != null
    }

    /** The address of a public node was typed in. Like a setup file, it is only checked here. */
    fun offerPublic(url: String): Boolean {
        state = try {
            state.copy(offered = Setup.publicNode(url), offeredFrom = null, offerError = null, offerDetail = "", problem = null, notice = null)
        } catch (e: SetupError) {
            state.copy(offered = null, offeredFrom = null, offerError = e.reason, offerDetail = e.detail, notice = null)
        }
        return state.offered != null
    }

    /** Reads a setup file the user picked. Only the beginning is read: a setup is small. */
    fun offerFile(uri: Uri) {
        viewModelScope.launch {
            val text = withContext(io) {
                try {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArray(Setup.MAX_CHARS * 4 + 1)
                        var n = 0
                        while (n < buffer.size) {
                            val got = input.read(buffer, n, buffer.size - n)
                            if (got < 0) break
                            n += got
                        }
                        buffer.decodeToString(0, n)
                    }
                } catch (e: Exception) {
                    null
                }
            }
            if (text == null) state = state.copy(notice = Notice.FILE_UNREADABLE) else offer(text, uri)
        }
    }

    fun dropOffer() {
        state = state.copy(offered = null, offeredFrom = null, offerError = null, offerDetail = "")
    }

    fun note(notice: Notice?) {
        state = state.copy(notice = notice, noticeDetail = "")
    }

    /** Stores the offered setup, and if asked deletes the file it came from: the password in it is plain text. */
    fun acceptOffer(deleteFile: Boolean) {
        val setup = state.offered ?: return
        val from = state.offeredFrom
        viewModelScope.launch {
            val saved = withContext(io) { runCatching { setups.save(setup) } }
            if (saved.isFailure) {
                state = state.copy(problem = Problem(Problem.Kind.STORE, saved.exceptionOrNull()!!.javaClass.simpleName))
                return@launch
            }
            var notice = state.notice
            if (deleteFile && from != null) {
                val gone = withContext(io) {
                    try { DocumentsContract.deleteDocument(getApplication<Application>().contentResolver, from) } catch (e: Exception) { false }
                }
                notice = if (gone) Notice.FILE_DELETED else Notice.FILE_NOT_DELETED
            }
            engine = Engine(setup)
            state = state.copy(setup = setup, offered = null, offeredFrom = null, offerError = null, snapshot = null, problem = null, notice = notice)
            refresh()
        }
    }

    /** Forgets the node and the watched addresses. The wallet, if there is one, stays; its owner is asked first. */
    fun forgetSetup(owner: Owner) {
        viewModelScope.launch {
            if (state.wallet != null && !runCatching { owner.present() }.getOrDefault(false)) return@launch
            withContext(io) { runCatching { setups.clear() } }
            engine = null
            state = state.copy(setup = null, snapshot = null, problem = null, draft = null, sentTxid = null)
        }
    }

    // ------------------------------------------------------------------------------------------ looking at the chain

    fun refresh() {
        relooks = 0
        look()
    }

    private fun look() {
        val engine = engine ?: return
        if (state.locked) return
        if (state.busy) {
            lookAgain = true            // a look is under way; what it shows may be older than this request, so another follows
            return
        }
        val wallet = state.wallet
        val mark = sends
        state = state.copy(busy = true, problem = null)
        viewModelScope.launch {
            val outcome = withContext(io) { runCatching { engine.fetch(wallet) } }
            if (engine !== this@WalletModel.engine || state.wallet?.xpub != wallet?.xpub) {
                // the node or the wallet was changed while this look was under way: what it saw belongs to neither
                lookAgain = false
                state = state.copy(busy = false)
                refresh()
                return@launch
            }
            val seen = outcome.getOrNull()
            if (seen == null) {
                lookAgain = false
                renumber = false
                state = state.copy(busy = false, problem = Problem.of(outcome.exceptionOrNull()!!))
                return@launch
            }
            state = state.copy(busy = false, snapshot = seen, problem = null)
            // a look that overlapped a payment being sent saw the node before or while the payment arrived there,
            // and a look during which a block arrived saw two different moments: neither may judge what waits
            val more = settle(seen, judge = mark == sends && !state.sending && seen.steady)
            if (lookAgain) {
                lookAgain = false
                refresh()
            } else if (more && relooks < MAX_RELOOKS) {
                relooks++
                look()
            }
        }
    }

    /**
     * Brings what the app remembers into line with what the chain shows. Waiting payments that are in a block
     * now are done. One that the node does not hold, that is in no block and whose coins nothing else is
     * spending did not go through, and its owner is told; but only when it is at least a minute old and two
     * looks in a row say so. And the wallet's key numbers move past the keys the chain shows as used, as far
     * as the app may look. Returns true when another look should follow at once.
     */
    private fun settle(seen: Snapshot, judge: Boolean): Boolean {
        val wallet = state.wallet ?: return false
        val engine = engine ?: return false
        val confirmed = seen.moves.map { it.txid }.toSet()
        var gone: Pending? = null
        var again = !seen.steady
        val missed = HashSet<String>()
        val checked = if (!judge) wallet.pending.take(Engine.MAX_CHECKED) else wallet.pending.take(Engine.MAX_CHECKED).mapNotNull { p ->
            when {
                p.txid in confirmed -> null
                p.txid in seen.held -> if (p.unsure) p.copy(unsure = false) else p
                // Neither in a block nor held by the node. That alone does not make it gone.
                // Something the node holds spends its coins: they are not free, and the next blocks will tell.
                p.inputs.any { it in seen.inWaiting } -> p
                // It was written down a moment ago and may still be on its way to the node that was asked.
                seen.atMillis - p.atMillis < goneAfterMillis -> p
                // One look that does not find it is not enough: the next one decides.
                p.txid !in doubted -> {
                    missed.add(p.txid)
                    again = true
                    p
                }
                else -> {
                    gone = p
                    null
                }
            }
        }
        if (judge) doubted = missed
        val waiting = checked + wallet.pending.drop(Engine.MAX_CHECKED)
        // The key numbers follow what the chain shows as used, but only as far as the app may look: what a node
        // shows is not checked against the work in its blocks, so it must not be able to move them far.
        val top = engine.allowed(wallet) - 1
        val afresh = renumber && judge
        if (afresh) renumber = false
        val receive = if (afresh) minOf(seen.nextReceive, top) else maxOf(wallet.receiveIndex, minOf(seen.nextReceive, top))
        val change = if (afresh) minOf(seen.nextChange, top) else maxOf(wallet.changeIndex, minOf(seen.nextChange, top))
        if (waiting == wallet.pending && receive == wallet.receiveIndex && change == wallet.changeIndex) {
            if (afresh) state = state.copy(notice = Notice.SAVED, noticeDetail = "")
            return again
        }
        val lost = gone
        change(
            {
                it.copy(
                    pending = waiting,
                    receiveIndex = if (afresh) receive else maxOf(it.receiveIndex, receive),
                    changeIndex = if (afresh) change else maxOf(it.changeIndex, change),
                )
            },
            if (lost != null) Notice.PAYMENT_GONE else if (afresh) Notice.SAVED else null, lost?.let { Format.coins(it.sentSat) }.orEmpty(),
        )
        return again
    }

    /** Called when the app comes back to the front: an old picture is replaced, a fresh one is left alone. */
    fun refreshIfStale(now: Long = System.currentTimeMillis()) {
        val at = state.snapshot?.atMillis
        if (state.setup != null && (at == null || now - at > STALE_MILLIS)) refresh()
    }

    // ------------------------------------------------------------------------------------------ the wallet's words

    /** Makes new words and shows them. Nothing is stored until the owner has written them down and confirmed. */
    fun startNewWallet() {
        if (state.wallet != null) return
        state = if (state.wordsWithoutWallet) state.copy(walletProblem = Problem(Problem.Kind.WORDS_EXIST))
        else state.copy(newWords = SecretWords(Words.generate()), walletProblem = null)
    }

    fun cancelNewWallet() {
        state = state.copy(newWords = null, walletProblem = null)
    }

    fun confirmNewWallet(owner: Owner) {
        val words = state.newWords?.words ?: return
        keepWords(words, owner)
    }

    fun restoreWallet(text: String, owner: Owner) {
        val words = Words.split(text)
        if (!Words.valid(words)) {
            state = state.copy(walletProblem = Problem(Problem.Kind.BAD_WORDS))
            return
        }
        keepWords(words, owner)
    }

    private fun walletProblemOf(t: Throwable): Problem = when (t) {
        is OwnerRefused -> Problem(Problem.Kind.UNLOCK, t.detail)
        is EngineError -> Problem(t.kind, t.detail)
        is android.security.keystore.KeyPermanentlyInvalidatedException, is KeyGone -> Problem(Problem.Kind.KEY_GONE)
        else -> Problem(Problem.Kind.STORE, t.javaClass.simpleName)
    }

    private fun keepWords(words: List<String>, owner: Owner) {
        val chain = state.setup?.chain ?: Chain.MAIN
        viewModelScope.launch {
            val outcome = runCatching {
                // words that are already stored are never written over: they may be the only copy somebody has
                if (withContext(io) { vault.exists() }) throw EngineError(Problem.Kind.WORDS_EXIST)
                val cipher = withContext(io) { vault.encryptor() }
                val unlocked = owner.unlock(cipher)
                withContext(io) {
                    vault.store(unlocked, words)
                    WalletInfo(chain, Account.fromWords(words, chain).watchKey().xpub).also { wallets.save(it) }
                }
            }
            state = outcome.fold(
                { state.copy(wallet = it, newWords = null, walletProblem = null, wordsWithoutWallet = false, notice = Notice.WALLET_READY) },
                { state.copy(walletProblem = walletProblemOf(it), wordsWithoutWallet = state.wordsWithoutWallet || (it is EngineError && it.kind == Problem.Kind.WORDS_EXIST)) },
            )
            if (state.wallet != null) refresh()
        }
    }

    /**
     * The recovery words are stored, but the wallet's own file is missing or cannot be read. The wallet is
     * built again from the stored words, which the owner unlocks. Its key numbers are found from the chain.
     */
    fun rebuildWallet(owner: Owner) {
        val chain = state.setup?.chain ?: Chain.MAIN
        viewModelScope.launch {
            val outcome = runCatching {
                val cipher = withContext(io) { vault.decryptor() }
                val unlocked = owner.unlock(cipher)
                withContext(io) {
                    WalletInfo(chain, Account.fromWords(vault.open(unlocked), chain).watchKey().xpub).also { wallets.save(it) }
                }
            }
            state = outcome.fold(
                { state.copy(wallet = it, walletProblem = null, wordsWithoutWallet = false, notice = Notice.WALLET_READY) },
                { state.copy(walletProblem = walletProblemOf(it)) },
            )
            if (state.wallet != null) refresh()
        }
    }

    /** Destroys recovery words that are stored without a wallet. Only for an owner who has them on paper, or does not need them. */
    fun discardStoredWords(owner: Owner) {
        viewModelScope.launch {
            if (!runCatching { owner.present() }.getOrDefault(false)) return@launch
            withContext(io) { runCatching { vault.destroy() } }
            state = state.copy(wordsWithoutWallet = false, walletProblem = null, notice = Notice.WORDS_REMOVED)
        }
    }

    /** Removes the wallet from this phone: the sealed words, their key and the public information. The coins stay on the chain. */
    fun removeWallet(owner: Owner) {
        viewModelScope.launch {
            if (!runCatching { owner.present() }.getOrDefault(false)) return@launch
            withContext(io) {
                runCatching { vault.destroy() }
                runCatching { wallets.clear() }
            }
            state = state.copy(wallet = null, draft = null, sentTxid = null, locked = false, wordsWithoutWallet = false, notice = Notice.WALLET_REMOVED)
            refresh()
        }
    }

    private fun change(update: (WalletInfo) -> WalletInfo, notice: Notice? = null, detail: String = "") {
        val next = update(state.wallet ?: return)
        state = state.copy(wallet = next, notice = notice ?: state.notice, noticeDetail = if (notice != null) detail else state.noticeDetail)
        viewModelScope.launch { persist() }
    }

    /** Writes the wallet's file as it stands now. Writes follow one another, and each writes the newest state. */
    private suspend fun persist(): Boolean = saving.withLock {
        val latest = state.wallet ?: return@withLock true
        val done = withContext(io) { runCatching { wallets.save(latest) } }
        if (done.isFailure) state = state.copy(problem = Problem(Problem.Kind.STORE, done.exceptionOrNull()!!.javaClass.simpleName))
        done.isSuccess
    }

    /** Moves on to the next receiving address, but not further than a wallet brought back from its words would look. */
    fun nextAddress() {
        val wallet = state.wallet ?: return
        val used = state.snapshot?.nextReceive ?: 0
        // the next address must be one that the app looks at: as far as it looks by itself, or as the owner has allowed
        val looked = engine?.allowed(wallet.copy(receiveIndex = 0)) ?: Engine.AUTO_PUBLIC
        state = when {
            wallet.receiveIndex + 1 >= used + WalletInfo.GAP -> state.copy(notice = Notice.ADDRESS_LIMIT, noticeDetail = "")
            wallet.receiveIndex + 1 >= looked -> state.copy(notice = Notice.LOOK_LIMIT, noticeDetail = "")
            else -> return change({ it.copy(receiveIndex = it.receiveIndex + 1) })
        }
    }

    /**
     * The owner allows the app to look at more keys of the wallet than it does by itself: twice as many as
     * now. For a wallet that has used many addresses, or whose coins are not all found after it was brought
     * back from its words.
     */
    fun lookFurther() {
        val engine = engine ?: return
        val wallet = state.wallet ?: return
        val now = maxOf(state.snapshot?.lookedTo ?: 0, engine.windowOf(wallet))
        val to = minOf(WalletInfo.MAX_LOOK, now * 2)
        if (to <= now) return
        change({ it.copy(lookTo = maxOf(it.lookTo, to)) })
        refresh()
    }

    /**
     * Works the wallet's key numbers out again from what the node shows now. For a wallet whose numbers were
     * moved ahead by a node that did not show the truth: with an honest node they return to the first unused key.
     */
    fun renumberKeys() {
        if (state.wallet == null || engine == null) return
        renumber = true
        refresh()
    }

    fun saveMining(hashrate: String) = change({ it.copy(hashrate = hashrate.trim().take(20)) }, Notice.SAVED)

    /** The price is kept only if it is a plain number; anything else could not be shown. */
    fun savePrice(price: String, currency: String) {
        val plain = price.trim().replace(',', '.')
        if (plain.isNotEmpty() && !Regex("[0-9]{1,12}(\\.[0-9]{1,8})?").matches(plain)) {
            state = state.copy(walletProblem = Problem(Problem.Kind.BAD_PRICE))
            return
        }
        state = state.copy(walletProblem = null)
        change({ it.copy(price = plain, currency = currency.trim().uppercase().filter { c -> c.isLetterOrDigit() }.take(6)) }, Notice.SAVED)
    }

    /** Switching the lock off is something only the owner may do. */
    fun saveLock(on: Boolean, owner: Owner) {
        viewModelScope.launch {
            if (!on && !runCatching { owner.present() }.getOrDefault(false)) return@launch
            change({ it.copy(lockOnOpen = on) })
        }
    }

    // ------------------------------------------------------------------------------------------ paying

    /** Works a payment out from a fresh look at the coins. Nothing is signed or sent here. */
    fun prepare(to: String, amount: String, everything: Boolean, feeRate: Long) {
        val engine = engine ?: return
        val wallet = state.wallet ?: return
        val sat = if (everything) null else Format.parseCoins(amount)
        if (!everything && sat == null) {
            state = state.copy(sendProblem = Problem(Problem.Kind.BAD_AMOUNT), draft = null)
            return
        }
        state = state.copy(sending = true, sendProblem = null, draft = null, sentTxid = null)
        viewModelScope.launch {
            val used = state.pending.flatMap { it.inputs }.toSet()
            val outcome = withContext(io) { runCatching { engine.plan(wallet, to, sat, feeRate, used) } }
            state = outcome.fold(
                { state.copy(sending = false, draft = Draft(to.trim(), it, everything)) },
                { state.copy(sending = false, sendProblem = Problem.of(it)) },
            )
            lockIfWanted()
        }
    }

    fun dropDraft() {
        state = state.copy(draft = null, sendProblem = null)
    }

    /**
     * The owner said yes: unlock the words, sign, let the node check the transaction, send it.
     *
     * The payment is written into the wallet's file before the transaction is handed to the node. Whatever
     * happens after that (the app is closed, the connection drops, the answer is lost), the app still knows
     * that this payment may be on its way and does not build another one on the same coins.
     */
    fun confirmSend(owner: Owner) {
        val engine = engine ?: return
        val wallet = state.wallet ?: return
        val draft = state.draft ?: return
        if (state.sending) return
        if (wallet.pending.size >= WalletInfo.MAX_PENDING) {
            // more waiting payments than the wallet's file can hold are not started; they clear with the next blocks
            state = state.copy(sendProblem = Problem(Problem.Kind.TOO_MANY_WAITING))
            return
        }
        state = state.copy(sending = true, sendProblem = null)
        sends++
        viewModelScope.launch {
            try {
                sendNow(engine, wallet, draft, owner)
            } finally {
                sends++
                if (state.sending) state = state.copy(sending = false)
                lockIfWanted()
                refresh()               // after the payment is over, so that this look may judge what waits
            }
        }
    }

    private suspend fun sendNow(engine: Engine, wallet: WalletInfo, draft: Draft, owner: Owner) {
        run {
            val signing = runCatching {
                val cipher = withContext(io) { vault.decryptor() }
                val unlocked = owner.unlock(cipher)
                withContext(io) { engine.sign(wallet.xpub, draft.plan, vault.open(unlocked)) }
            }
            val signed = signing.getOrNull()
            if (signed == null) {
                val t = signing.exceptionOrNull()!!
                state = state.copy(sending = false, sendProblem = if (t is OwnerRefused) Problem(Problem.Kind.UNLOCK, t.detail) else Problem.of(t))
                return
            }
            val plan = draft.plan
            val record = Pending(
                signed.txid, if (draft.everything) plan.payments[0].amountSat else plan.sentSat, plan.feeSat,
                plan.inputs.map { it.txid + ":" + it.vout }.toSet(), unsure = true, atMillis = System.currentTimeMillis(),
            )
            val before = state.wallet ?: wallet
            // The change key moves on for the next payment, but never further than a wallet brought back from its
            // words would look (twenty past the last key the chain shows as used), nor past what the app looks at.
            val top = minOf(engine.allowed(before) - 1, (state.snapshot?.nextChange ?: 0) + WalletInfo.GAP - 1)
            val moved = if (plan.changeSat > 0) maxOf(before.changeIndex, minOf(before.changeIndex + 1, top)) else before.changeIndex
            state = state.copy(wallet = before.copy(pending = before.pending + record, changeIndex = moved))
            if (!persist()) {
                // it could not be written down, so it is not sent: better no payment than one the app would not know about
                state = state.copy(wallet = before, sending = false, sendProblem = Problem(Problem.Kind.STORE, "wallet"))
                return
            }
            val delivery = withContext(io) { runCatching { engine.deliver(signed) } }
            if (delivery.isFailure) {
                // The node itself refused it: it is certain that it did not go out. The note about it is taken
                // back, and so is the change key, which nothing has used.
                change({
                    it.copy(
                        pending = it.pending.filter { p -> p.txid != record.txid },
                        changeIndex = if (it.changeIndex == moved) before.changeIndex else it.changeIndex,
                    )
                })
                state = state.copy(sending = false, sendProblem = refusal(delivery.exceptionOrNull()!!))
            } else if (delivery.getOrThrow()) {
                change({ it.copy(pending = it.pending.map { p -> if (p.txid == record.txid) p.copy(unsure = false) else p }) })
                state = state.copy(sending = false, draft = null, sentTxid = record.txid)
            } else {
                // the node did not say. The payment stays written down as "outcome not known" until a look at the node settles it.
                state = state.copy(sending = false, draft = null, sendProblem = Problem(Problem.Kind.UNSURE, record.txid))
            }
        }
    }

    /** Why a payment was refused. Only what the node itself decided is thrown as a refusal, so nothing was sent. */
    private fun refusal(t: Throwable): Problem = if (t is NodeError) Problem(Problem.Kind.REJECTED, t.message.orEmpty()) else Problem.of(t)

    fun clearSent() {
        state = state.copy(sentTxid = null, sendProblem = null)
    }

    // ------------------------------------------------------------------------------------------ a paper address

    /** Looks at what the words of a paper address hold. The words stay in memory until the address is emptied, the look is dropped, or the app locks. */
    fun paperLook(text: String) {
        val engine = engine ?: return
        val words = Words.split(text)
        if (!Words.valid(words)) {
            state = state.copy(paperProblem = Problem(Problem.Kind.BAD_WORDS))
            return
        }
        paperSpent = emptySet()
        look(engine, words)
    }

    private fun look(engine: Engine, words: List<String>) {
        state = state.copy(paperBusy = true, paperProblem = null, paper = null, paperDraft = null)
        viewModelScope.launch {
            val spent = paperSpent
            val outcome = withContext(io) { runCatching { engine.paperLook(words, spent) } }
            paperWords = if (outcome.isSuccess) words else null
            state = outcome.fold({ state.copy(paperBusy = false, paper = it) }, { state.copy(paperBusy = false, paperProblem = Problem.of(it)) })
            lockIfWanted()
        }
    }

    /** [keep] empty: everything goes to [to]. Otherwise [keep] goes to [to] and the rest to [restTo]. */
    fun paperPrepare(to: String, keep: String, restTo: String, feeRate: Long) {
        val engine = engine ?: return
        val found = state.paper ?: return
        val keepSat = if (keep.isBlank()) null else Format.parseCoins(keep)
        if (keep.isNotBlank() && keepSat == null) {
            state = state.copy(paperProblem = Problem(Problem.Kind.BAD_AMOUNT), paperDraft = null)
            return
        }
        val rest = restTo.trim().ifBlank { null }
        state = try {
            state.copy(paperDraft = PaperDraft(engine.paperPlan(found, to, feeRate, keepSat, rest), to.trim(), if (keepSat == null) null else rest), paperProblem = null)
        } catch (e: Exception) {
            state.copy(paperDraft = null, paperProblem = Problem.of(e))
        }
    }

    /** A field of the paper form was changed: what was worked out before no longer stands. */
    fun paperEdited() {
        if (state.paperDraft != null && !state.paperBusy) state = state.copy(paperDraft = null)
    }

    fun paperSend() {
        val engine = engine ?: return
        val found = state.paper ?: return
        val draft = state.paperDraft ?: return
        val words = paperWords ?: return
        if (state.paperBusy) return
        state = state.copy(paperBusy = true, paperProblem = null)
        viewModelScope.launch {
            val signing = withContext(io) { runCatching { engine.paperSign(found, draft.plan, words) } }
            val signed = signing.getOrNull()
            if (signed == null) {
                state = state.copy(paperBusy = false, paperProblem = Problem.of(signing.exceptionOrNull()!!))
                lockIfWanted()
                return@launch
            }
            val delivery = withContext(io) { runCatching { engine.deliver(signed) } }
            when {
                delivery.isFailure -> state = state.copy(paperBusy = false, paperProblem = refusal(delivery.exceptionOrNull()!!))
                delivery.getOrThrow() -> {
                    val more = draft.plan.leftBehind > 0
                    paperSpent = paperSpent + draft.plan.inputs.map { it.txid + ":" + it.vout }
                    state = state.copy(paperBusy = false, paper = null, paperDraft = null, paperTxid = signed.txid)
                    // more coins than one transaction takes: the words stay for the next part, and the address is looked at again
                    if (more && !lockWanted) look(engine, words) else paperWords = null
                    refresh()
                }
                else -> {
                    paperWords = null
                    state = state.copy(paperBusy = false, paper = null, paperDraft = null, paperProblem = Problem(Problem.Kind.UNSURE, signed.txid))
                }
            }
            lockIfWanted()
        }
    }

    fun paperDrop() {
        paperWords = null
        paperSpent = emptySet()
        state = state.copy(paper = null, paperDraft = null, paperProblem = null, paperTxid = null, paperBusy = false)
    }

    // ------------------------------------------------------------------------------------------ self-test

    /**
     * Derives an address and signs a transaction with the keys of the public BIP39 test phrase and compares
     * both with the published answers (see SelfCheck). It shows whether the key code and the native library
     * work on this phone. It touches no key of the user.
     */
    fun runSelfTest() {
        if (state.selfTest == SelfTest.RUNNING) return
        state = state.copy(selfTest = SelfTest.RUNNING, selfTestDetail = "")
        viewModelScope.launch {
            val result = withContext(Dispatchers.Default) {
                try {
                    SelfCheck.run()
                } catch (t: Throwable) {                // also a native library that does not load
                    t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "")
                }
            }
            state = if (result == null) state.copy(selfTest = SelfTest.PASSED) else state.copy(selfTest = SelfTest.FAILED, selfTestDetail = result)
        }
    }

    companion object {
        const val STALE_MILLIS = 60_000L
        const val LOCK_AFTER_MILLIS = 60_000L
        const val GONE_AFTER_MILLIS = 60_000L
        const val MAX_RELOOKS = 2
        val FEE_RATES = listOf(1L, Planner.DEFAULT_FEE_RATE, 5L)
    }
}
