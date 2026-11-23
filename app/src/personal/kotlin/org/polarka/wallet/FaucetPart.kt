package org.polarka.wallet

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The faucet's question, held by the activity and not by the page: leaving the receive page neither orphans it nor
 * lets a second one start beside it, and the owner can stop it. Only in the personal edition.
 */
class FaucetModel : ViewModel() {
    /** What the faucet said last: the id of its transaction, or why there is none. */
    data class Said(val txid: String? = null, val refused: Faucet.Refused? = null)

    var busy by mutableStateOf(false)
        private set
    var said by mutableStateOf<Said?>(null)
        private set
    private var asking: Faucet.Asking? = null

    fun ask(address: String) {
        if (busy) return
        val now = Faucet.Asking()
        asking = now
        busy = true
        said = null
        viewModelScope.launch {
            // the network waits and the work both stand on threads made for waiting, not on the few that compute
            val outcome = withContext(Dispatchers.IO) {
                try { Said(txid = Faucet.ask(address, base, now)) } catch (e: Faucet.Refused) { Said(refused = e) }
            }
            if (asking === now) {
                said = outcome.takeIf { it.refused?.reason != "STOPPED" }
                busy = false
                asking = null
            }
        }
    }

    fun stop() {
        asking?.stop()
    }

    override fun onCleared() {
        asking?.stop()
    }

    companion object {
        /** Where the faucet is. The tests put a stand-in here. */
        @Volatile internal var base = Faucet.URL_DEFAULT
    }
}

/** The faucet of the personal edition: a first coin to the address shown, after some work done by the phone. */
@Composable
internal fun FaucetPart(address: String) {
    val faucet = viewModel<FaucetModel>()
    Block(stringResource(R.string.faucet_title)) {
        Text(stringResource(R.string.faucet_text), style = MaterialTheme.typography.bodySmall)
        if (faucet.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(stringResource(R.string.faucet_busy), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = faucet::stop, modifier = Modifier.testTag("faucet-stop")) { Text(stringResource(R.string.faucet_stop)) }
        } else {
            Button(onClick = { faucet.ask(address) }, modifier = Modifier.testTag("faucet-go")) { Text(stringResource(R.string.faucet_go)) }
        }
        faucet.said?.let { said ->
            val txid = said.txid
            val refused = said.refused
            if (txid != null) {
                Text(stringResource(R.string.faucet_done))
                Mono(txid, "faucet-txid")
            } else if (refused != null) {
                Text(refusedText(refused), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("faucet-refused"))
            }
        }
    }
}

/** The faucet's own words are named as its words; what the phone found by itself is said as the phone's. */
@Composable
private fun refusedText(e: Faucet.Refused): String = when {
    e.byFaucet && e.reason == "NOT_NOW" -> stringResource(R.string.faucet_not_now)
    e.byFaucet -> stringResource(R.string.faucet_refused, e.reason)
    e.reason == "SLOW" -> stringResource(R.string.faucet_slow)
    e.reason == "UNREACHABLE" -> stringResource(R.string.faucet_unreachable)
    e.reason == "TOO_MUCH" -> stringResource(R.string.faucet_too_much)
    e.reason == "RAN_OUT" -> stringResource(R.string.faucet_ran_out)
    e.reason == "NO_ANSWER" -> stringResource(R.string.faucet_no_answer)
    else -> stringResource(R.string.faucet_unreadable)
}
