package io.github.severka_monitor.northwatch

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import north.core.Account
import north.core.Chain
import north.core.Plan
import north.core.Planner
import north.core.SelfCheck
import north.core.Words
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
enum class Notice { FILE_DELETED, FILE_NOT_DELETED, CLIPBOARD_CLEARED, FILE_UNREADABLE, CLIPBOARD_EMPTY, WALLET_READY, WALLET_REMOVED, SAVED }

/** Recovery words on their way through the app's state. A printed state or a log line never shows them. */
class SecretWords(val words: List<String>) {
    override fun toString(): String = "<recovery words>"
}

/** A payment that was sent and is not in a block yet. Its coins are not offered for the next payment. */
data class Pending(val txid: String, val sentSat: Long, val feeSat: Long, val inputs: Set<String>)

/** A payment worked out and waiting for the owner's yes. */
data class Draft(val to: String, val plan: Plan, val everything: Boolean)

data class WalletState(
    val loaded: Boolean = false,
    val locked: Boolean = false,
    val setup: Setup? = null,
    val offered: Setup? = null,                 // read from a file or the clipboard, not saved yet
    val offerError: SetupError.Reason? = null,
    val offerDetail: String = "",
    val offeredFrom: Uri? = null,
    val notice: Notice? = null,
    val wallet: WalletInfo? = null,
    val newWords: SecretWords? = null,          // a wallet being made: shown once, not stored yet
    val walletProblem: Problem? = null,
    val busy: Boolean = false,
    val snapshot: Snapshot? = null,
    val problem: Problem? = null,
    val draft: Draft? = null,
    val sending: Boolean = false,
    val sendProblem: Problem? = null,
    val sentTxid: String? = null,
    val pending: List<Pending> = emptyList(),
    val paper: PaperFound? = null,
    val paperDraft: Plan? = null,
    val paperBusy: Boolean = false,
    val paperProblem: Problem? = null,
    val paperTxid: String? = null,
    val selfTest: SelfTest = SelfTest.NOT_RUN,
    val selfTestDetail: String = "",
)

class WalletModel(app: Application) : AndroidViewModel(app) {
    private val setups: SetupStore = Stores.open(app)
    private val wallets: WalletStore = Stores.wallet(app)
    private val vault: WordVault = Stores.words(app)
    private var engine: Engine? = null
    private var paperWords: List<String>? = null        // only between looking at a paper address and emptying it
    var io: CoroutineDispatcher = Dispatchers.IO

    var state by mutableStateOf(WalletState())
        private set

    init {
        viewModelScope.launch {
            val stored = withContext(io) { runCatching { setups.load() to wallets.load() } }
            state = stored.fold(
                { (setup, wallet) -> state.copy(loaded = true, setup = setup, wallet = wallet, locked = wallet != null && wallet.lockOnOpen) },
                { state.copy(loaded = true, problem = Problem(Problem.Kind.STORE, it.javaClass.simpleName)) },
            )
            state.setup?.let { engine = Engine(it) }
            if (!state.locked) refresh()
        }
    }

    // ------------------------------------------------------------------------------------------ lock

    fun unlockApp(owner: Owner) {
        viewModelScope.launch {
            if (runCatching { owner.present() }.getOrDefault(false)) {
                state = state.copy(locked = false)
                refresh()
            }
        }
    }

    /** Called when the app has been in the background for a while. */
    fun lock() {
        val wallet = state.wallet ?: return
        if (wallet.lockOnOpen && state.newWords == null && !state.sending && !state.paperBusy) state = state.copy(locked = true, draft = null)
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
        state = state.copy(notice = notice)
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

    /** Forgets the node and the watched addresses. The wallet, if there is one, stays. */
    fun forgetSetup() {
        viewModelScope.launch {
            withContext(io) { runCatching { setups.clear() } }
            engine = null
            state = state.copy(setup = null, snapshot = null, problem = null, draft = null, sentTxid = null)
        }
    }

    // ------------------------------------------------------------------------------------------ looking at the chain

    fun refresh() {
        val engine = engine ?: return
        if (state.busy || state.locked) return
        val wallet = state.wallet
        state = state.copy(busy = true, problem = null)
        viewModelScope.launch {
            val outcome = withContext(io) { runCatching { engine.fetch(wallet) } }
            state = outcome.fold(
                { seen ->
                    val confirmed = seen.moves.map { it.txid }.toSet()
                    state.copy(busy = false, snapshot = seen, problem = null, pending = state.pending.filter { it.txid !in confirmed })
                },
                { state.copy(busy = false, problem = Problem.of(it)) },
            )
        }
    }

    /** Called when the app comes back to the front: an old picture is replaced, a fresh one is left alone. */
    fun refreshIfStale(now: Long = System.currentTimeMillis()) {
        val at = state.snapshot?.atMillis
        if (state.setup != null && (at == null || now - at > STALE_MILLIS)) refresh()
    }

    // ------------------------------------------------------------------------------------------ the wallet's words

    /** Makes new words and shows them. Nothing is stored until the owner has written them down and confirmed. */
    fun startNewWallet() {
        if (state.wallet == null) state = state.copy(newWords = SecretWords(Words.generate()), walletProblem = null)
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

    private fun keepWords(words: List<String>, owner: Owner) {
        val chain = state.setup?.chain ?: Chain.MAIN
        viewModelScope.launch {
            val outcome = runCatching {
                val cipher = withContext(io) { vault.encryptor() }
                val unlocked = owner.unlock(cipher)
                withContext(io) {
                    vault.store(unlocked, words)
                    WalletInfo(chain, Account.fromWords(words, chain).watchKey().xpub).also { wallets.save(it) }
                }
            }
            state = outcome.fold(
                { state.copy(wallet = it, newWords = null, walletProblem = null, notice = Notice.WALLET_READY) },
                { state.copy(walletProblem = if (it is OwnerRefused) Problem(Problem.Kind.UNLOCK, it.detail) else Problem(Problem.Kind.STORE, it.javaClass.simpleName)) },
            )
            if (state.wallet != null) refresh()
        }
    }

    /** Removes the wallet from this phone: the sealed words, their key and the public information. The coins stay on the chain. */
    fun removeWallet() {
        viewModelScope.launch {
            withContext(io) {
                runCatching { vault.destroy() }
                runCatching { wallets.clear() }
            }
            state = state.copy(wallet = null, draft = null, sentTxid = null, pending = emptyList(), locked = false, notice = Notice.WALLET_REMOVED)
            refresh()
        }
    }

    private fun change(update: (WalletInfo) -> WalletInfo, notice: Notice? = null) {
        val next = update(state.wallet ?: return)
        state = state.copy(wallet = next, notice = notice ?: state.notice)
        viewModelScope.launch { withContext(io) { runCatching { wallets.save(next) } } }
    }

    /** Moves on to the next receiving address. */
    fun nextAddress() = change({ it.copy(receiveIndex = it.receiveIndex + 1) })

    fun saveMining(hashrate: String) = change({ it.copy(hashrate = hashrate.trim()) }, Notice.SAVED)

    fun savePrice(price: String, currency: String) = change({ it.copy(price = price.trim().replace(',', '.'), currency = currency.trim().uppercase().take(6)) }, Notice.SAVED)

    fun saveLock(on: Boolean) = change({ it.copy(lockOnOpen = on) })

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
        }
    }

    fun dropDraft() {
        state = state.copy(draft = null, sendProblem = null)
    }

    /** The owner said yes: unlock the words, sign, let the node check the transaction, send it. */
    fun confirmSend(owner: Owner) {
        val engine = engine ?: return
        val wallet = state.wallet ?: return
        val draft = state.draft ?: return
        if (state.sending) return
        state = state.copy(sending = true, sendProblem = null)
        viewModelScope.launch {
            val outcome = runCatching {
                val cipher = withContext(io) { vault.decryptor() }
                val unlocked = owner.unlock(cipher)
                withContext(io) { engine.send(wallet.xpub, draft.plan, vault.open(unlocked)) }
            }
            if (outcome.isSuccess) {
                if (draft.plan.changeSat > 0) change({ it.copy(changeIndex = it.changeIndex + 1) })
                val plan = draft.plan
                val sent = Pending(
                    outcome.getOrThrow(), if (draft.everything) plan.payments[0].amountSat else plan.sentSat, plan.feeSat,
                    plan.inputs.map { it.txid + ":" + it.vout }.toSet(),
                )
                state = state.copy(sending = false, draft = null, sentTxid = sent.txid, pending = state.pending + sent)
                refresh()
            } else {
                val t = outcome.exceptionOrNull()!!
                state = state.copy(sending = false, sendProblem = if (t is OwnerRefused) Problem(Problem.Kind.UNLOCK, t.detail) else Problem.of(t))
            }
        }
    }

    fun clearSent() {
        state = state.copy(sentTxid = null)
    }

    // ------------------------------------------------------------------------------------------ a paper address

    /** Looks at what the words of a paper address hold. The words stay in memory until the address is emptied or the look is dropped. */
    fun paperLook(text: String) {
        val engine = engine ?: return
        val words = Words.split(text)
        if (!Words.valid(words)) {
            state = state.copy(paperProblem = Problem(Problem.Kind.BAD_WORDS))
            return
        }
        state = state.copy(paperBusy = true, paperProblem = null, paper = null, paperDraft = null, paperTxid = null)
        viewModelScope.launch {
            val outcome = withContext(io) { runCatching { engine.paperLook(words) } }
            paperWords = if (outcome.isSuccess) words else null
            state = outcome.fold({ state.copy(paperBusy = false, paper = it) }, { state.copy(paperBusy = false, paperProblem = Problem.of(it)) })
        }
    }

    /** [keep] empty: everything goes to [to]. Otherwise [keep] goes to [to] and the rest to [restTo]. */
    fun paperPrepare(to: String, keep: String, restTo: String, feeRate: Long) {
        val engine = engine ?: return
        val found = state.paper ?: return
        val keepSat = if (keep.isBlank()) null else Format.parseCoins(keep)
        if (keep.isNotBlank() && keepSat == null) {
            state = state.copy(paperProblem = Problem(Problem.Kind.BAD_AMOUNT))
            return
        }
        state = try {
            state.copy(paperDraft = engine.paperPlan(found, to, feeRate, keepSat, restTo.ifBlank { null }), paperProblem = null)
        } catch (e: Exception) {
            state.copy(paperDraft = null, paperProblem = Problem.of(e))
        }
    }

    fun paperSend() {
        val engine = engine ?: return
        val found = state.paper ?: return
        val plan = state.paperDraft ?: return
        val words = paperWords ?: return
        if (state.paperBusy) return
        state = state.copy(paperBusy = true, paperProblem = null)
        viewModelScope.launch {
            val outcome = withContext(io) { runCatching { engine.paperSend(found, plan, words) } }
            if (outcome.isSuccess) {
                paperWords = null
                state = state.copy(paperBusy = false, paper = null, paperDraft = null, paperTxid = outcome.getOrNull())
                refresh()
            } else {
                state = state.copy(paperBusy = false, paperProblem = Problem.of(outcome.exceptionOrNull()!!))
            }
        }
    }

    fun paperDrop() {
        paperWords = null
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
        val FEE_RATES = listOf(1L, Planner.DEFAULT_FEE_RATE, 5L)
    }
}
