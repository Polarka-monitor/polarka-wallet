@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.polarka.wallet

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import north.core.Plan
import north.core.Planner
import north.node.Movement

@Composable
private fun Page(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(screenPadding), verticalArrangement = gap) { content() }
}

@Composable
private fun Busy(text: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(stringResource(text), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun NoWallet(model: WalletModel, state: WalletState, owner: Owner) {
    if (state.wordsWithoutWallet) {
        // recovery words are stored, but the wallet's own file is missing or cannot be read: nothing here may write over them
        var askDiscard by rememberSaveable { mutableStateOf(false) }
        Block(stringResource(R.string.stored_words_title)) {
            Text(stringResource(R.string.stored_words_text))
            ProblemCard(state.walletProblem, "wallet-problem")
            Button(onClick = { model.rebuildWallet(owner) }, modifier = Modifier.fillMaxWidth().testTag("wallet-rebuild")) { Text(stringResource(R.string.stored_words_rebuild)) }
            TextButton(onClick = { askDiscard = true }, modifier = Modifier.fillMaxWidth().testTag("words-discard")) { Text(stringResource(R.string.stored_words_discard)) }
        }
        if (askDiscard) {
            AlertDialog(
                onDismissRequest = { askDiscard = false },
                title = { Text(stringResource(R.string.stored_words_discard)) },
                text = { Text(stringResource(R.string.wallet_remove_text)) },
                confirmButton = { TextButton(onClick = { askDiscard = false; model.discardStoredWords(owner) }, modifier = Modifier.testTag("words-discard-yes")) { Text(stringResource(R.string.wallet_remove_yes)) } },
                dismissButton = { TextButton(onClick = { askDiscard = false }) { Text(stringResource(R.string.cancel)) } },
            )
        }
        return
    }
    Block(stringResource(R.string.wallet_none_title)) {
        Text(stringResource(R.string.wallet_none_text))
        Button(onClick = model::startNewWallet, modifier = Modifier.fillMaxWidth().testTag("wallet-new")) { Text(stringResource(R.string.wallet_new)) }
        Text(stringResource(R.string.wallet_restore_hint), style = MaterialTheme.typography.bodySmall)
    }
}

/** The chain shows used addresses close to the last one the app looks at. The owner decides whether it looks further. */
@Composable
private fun LookFurther(model: WalletModel, snapshot: Snapshot) {
    Block(stringResource(R.string.further_title)) {
        Text(stringResource(R.string.further_text, snapshot.lookedTo.toString()), modifier = Modifier.testTag("further-text"))
        if (snapshot.lookedTo < WalletInfo.MAX_LOOK) {
            Button(onClick = model::lookFurther, modifier = Modifier.fillMaxWidth().testTag("look-further")) {
                Text(stringResource(R.string.further_go, minOf(WalletInfo.MAX_LOOK, snapshot.lookedTo * 2).toString()))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------- overview

@Composable
fun OverviewTab(model: WalletModel, state: WalletState, owner: Owner, go: (Section) -> Unit) {
    val setup = state.setup ?: return
    val snapshot = state.snapshot
    var askForgetPayment by rememberSaveable { mutableStateOf<String?>(null) }
    Page {
        Banner()
        NoticeCard(model, state)
        ProblemCard(state.problem)
        if (state.busy) Busy(R.string.asking_node)
        if (snapshot != null) {
            if (snapshot.info.syncing || snapshot.info.blocks < snapshot.info.headers) {
                Warning(stringResource(R.string.node_syncing, snapshot.info.blocks, snapshot.info.headers))
            }
            // everything the app sees, in large figures on the night itself; then the two things done most often
            Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.total), style = MaterialTheme.typography.labelLarge, color = Sky.quiet)
                BigAmount(Format.coins(snapshot.totalSat), "total")
                Format.value(snapshot.totalSat, state.wallet?.price.orEmpty(), state.wallet?.currency.orEmpty())?.let {
                    Text(stringResource(R.string.value_about, it), style = MaterialTheme.typography.bodyMedium, color = Sky.quiet, modifier = Modifier.testTag("value"))
                }
            }
            if (state.wallet != null) ReceiveAndSend({ go(Section.RECEIVE) }, { go(Section.SEND) })
        }
        state.pending.forEach { waiting ->
            Block(stringResource(if (waiting.unsure) R.string.pending_unsure_title else R.string.pending_title)) {
                Field(stringResource(R.string.send_amount), "-" + Format.coins(waiting.sentSat + waiting.feeSat), "pending-amount")
                Mono(waiting.txid)
                Text(stringResource(if (waiting.unsure) R.string.pending_unsure_text else R.string.pending_text), style = MaterialTheme.typography.bodySmall)
            }
        }
        state.dropped.forEach { gone ->
            Block(stringResource(R.string.dropped_title)) {
                Field(stringResource(R.string.send_amount), "-" + Format.coins(gone.sentSat + gone.feeSat), "dropped-amount")
                Mono(gone.txid)
                Text(stringResource(R.string.dropped_text), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("dropped-text"))
                TextButton(onClick = { askForgetPayment = gone.txid }, modifier = Modifier.testTag("dropped-forget")) { Text(stringResource(R.string.dropped_forget)) }
            }
        }
        if (state.wallet == null) {
            NoWallet(model, state, owner)
        } else {
            if (snapshot != null && snapshot.wantsFurther) LookFurther(model, snapshot)
            if (snapshot != null) {
                Block(stringResource(R.string.wallet_title)) {
                    // coins of a waiting payment that the node does not show as tied up are set aside all the same
                    val aside = state.asideSat
                    Field(stringResource(R.string.spendable), Format.coins(snapshot.spendableSat - aside), "wallet-spendable")
                    if (snapshot.waitingSat + aside > 0) Field(stringResource(R.string.in_waiting), Format.coins(snapshot.waitingSat + aside), "wallet-waiting")
                    if (snapshot.immatureSat > 0) Field(stringResource(R.string.immature), Format.coins(snapshot.immatureSat))
                }
            }
        }
        val watched = snapshot?.watched
        if (watched != null) {
            Block(stringResource(R.string.address_title)) {
                Field(stringResource(R.string.total), Format.coins(watched.totalSat), "watched-total")
                Field(stringResource(R.string.spendable), Format.coins(watched.spendableSat))
                if (watched.immature.isNotEmpty()) {
                    Field(stringResource(R.string.immature), Format.coins(watched.immatureSat))
                    val next = watched.immature.minOf { it.height } + Planner.MATURITY - watched.tip
                    Text(
                        stringResource(R.string.immature_note, watched.immature.size) + " " + pluralStringResource(R.plurals.next_matures, next, next),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                setup.addresses.forEach { Mono(it) }
            }
        }
        if (snapshot != null) {
            Recent(snapshot)
            Block(stringResource(R.string.node_title)) {
                Lit(stringResource(R.string.node_answers, snapshot.tip), "node-answers")
                Field(stringResource(R.string.node_address), setup.host)
                Field(stringResource(R.string.node_network), snapshot.info.chain)
                Field(stringResource(R.string.node_height), snapshot.tip.toString(), "height")
                Field(stringResource(R.string.node_updated), Format.clock(snapshot.atMillis))
            }
        }
    }
    askForgetPayment?.let { txid ->
        AlertDialog(
            onDismissRequest = { askForgetPayment = null },
            title = { Text(stringResource(R.string.dropped_forget_title)) },
            text = { Text(stringResource(R.string.dropped_forget_text)) },
            confirmButton = { TextButton(onClick = { askForgetPayment = null; model.forgetDropped(txid, owner) }, modifier = Modifier.testTag("dropped-forget-yes")) { Text(stringResource(R.string.dropped_forget_yes)) } },
            dismissButton = { TextButton(onClick = { askForgetPayment = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** The last few movements, right on the overview. The whole list is on the History page. */
@Composable
private fun Recent(snapshot: Snapshot) {
    val last = remember(snapshot) {
        snapshot.moves.ifEmpty { snapshot.watchedMoves }.sortedWith(compareByDescending<Movement> { it.height }.thenBy { it.txid }).take(3)
    }
    if (last.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.recent_title), style = MaterialTheme.typography.labelLarge, color = Sky.quiet, modifier = Modifier.padding(bottom = 6.dp))
        last.forEach { m ->
            HorizontalDivider(color = Sky.line)
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val what = stringResource(if (m.coinbase) R.string.output_reward else if (m.netSat >= 0) R.string.history_received else R.string.history_paid)
                    Text(what.replaceFirstChar { it.titlecase() }, style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.recent_block, m.height), style = MaterialTheme.typography.bodySmall, color = Sky.quiet)
                }
                Text(Format.signed(m.netSat), style = MaterialTheme.typography.bodyMedium.merge(figures), color = if (m.netSat >= 0) Sky.star else Sky.text, modifier = Modifier.testTag("recent-amount"))
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------- receive

@Composable
fun ReceiveTab(model: WalletModel, state: WalletState, owner: Owner) {
    val wallet = state.wallet
    val context = LocalContext.current
    Page {
        NoticeCard(model, state)
        if (wallet == null) {
            NoWallet(model, state, owner)
            return@Page
        }
        val address = remember(wallet.xpub, wallet.receiveIndex) { wallet.watchKey().address(0, wallet.receiveIndex) }
        Block(stringResource(R.string.receive_title)) {
            QrCode(address, Modifier.fillMaxWidth().padding(horizontal = 24.dp))
            Mono(address, "receive-address")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { copyToClipboard(context, "NORTH address", address) }) { Text(stringResource(R.string.copy)) }
                OutlinedButton(onClick = model::nextAddress, modifier = Modifier.testTag("next-address")) { Text(stringResource(R.string.receive_next)) }
            }
            Text(stringResource(R.string.receive_note, wallet.receiveIndex + 1), style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------------------------------------------------------------------------------------------- send

@Composable
private fun FeeChips(rate: Long, choose: (Long) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.send_fee_rate))
        WalletModel.FEE_RATES.forEach { r -> FilterChip(selected = r == rate, onClick = { choose(r) }, label = { Text(r.toString()) }) }
    }
}

@Composable
private fun PlanFields(plan: Plan, to: String, sentSat: Long, showChange: Boolean = true) {
    Text(stringResource(R.string.send_to), style = MaterialTheme.typography.labelMedium)
    Mono(to)
    Field(stringResource(R.string.send_amount), Format.coins(sentSat), "plan-amount")
    Field(stringResource(R.string.send_fee), Format.coins(plan.feeSat), "plan-fee")
    if (showChange && plan.changeSat > 0) Field(stringResource(R.string.send_change), Format.coins(plan.changeSat))
    Field(stringResource(R.string.send_inputs), plan.inputs.size.toString())
    Field(stringResource(R.string.send_size), plan.vsize.toString() + " vB")
}

@Composable
fun SendTab(model: WalletModel, state: WalletState, owner: Owner) {
    val context = LocalContext.current
    var to by rememberSaveable { mutableStateOf("") }
    var amount by rememberSaveable { mutableStateOf("") }
    var everything by rememberSaveable { mutableStateOf(false) }
    var rate by rememberSaveable { mutableStateOf(Planner.DEFAULT_FEE_RATE) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    var scanNote by remember { mutableStateOf<Int?>(null) }
    val chain = state.setup?.chain
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanning = true else scanNote = R.string.scan_denied
    }
    Page {
        if (state.wallet == null) {
            NoWallet(model, state, owner)
            return@Page
        }
        state.sentTxid?.let { txid ->
            Block(stringResource(R.string.sent_title)) {
                Text(stringResource(R.string.sent_text))
                Mono(txid, "sent-txid")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { copyToClipboard(context, "txid", txid) }) { Text(stringResource(R.string.copy)) }
                    TextButton(onClick = { model.clearSent(); to = ""; amount = ""; everything = false }) { Text(stringResource(R.string.ok)) }
                }
            }
        }
        val draft = state.draft
        if (draft == null) {
            Block(stringResource(R.string.send_title)) {
                state.snapshot?.let { Field(stringResource(R.string.spendable), Format.coins(it.spendableSat - state.asideSat)) }
                OutlinedTextField(
                    value = to, onValueChange = { to = it.trim() }, label = { Text(stringResource(R.string.send_to)) },
                    modifier = Modifier.fillMaxWidth().testTag("send-to"), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { readClipboard(context)?.let { text -> to = chain?.let { Scan.address(text, it) } ?: text.trim() } }) { Text(stringResource(R.string.send_paste)) }
                    TextButton(onClick = { scanNote = null; camera.launch(Manifest.permission.CAMERA) }, modifier = Modifier.testTag("scan")) { Text(stringResource(R.string.scan_button)) }
                }
                if (scanning) {
                    QrScanner(onText = { text ->
                        val address = chain?.let { Scan.address(text, it) }
                        if (address != null) {
                            to = address
                            scanning = false
                            scanNote = null
                        } else {
                            scanNote = R.string.scan_not_address
                        }
                    })
                    TextButton(onClick = { scanning = false }) { Text(stringResource(R.string.scan_close)) }
                }
                scanNote?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it }, enabled = !everything, singleLine = true,
                    label = { Text(stringResource(R.string.send_amount)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag("send-amount"),
                )
                Tick(everything, { everything = it }, stringResource(R.string.send_everything), "send-all")
                FeeChips(rate) { rate = it }
                ProblemCard(state.sendProblem, "send-problem")
                if (state.sending) Busy(R.string.asking_node)
                Button(onClick = { model.prepare(to, amount, everything, rate) }, enabled = !state.sending && to.isNotEmpty(), modifier = Modifier.fillMaxWidth().testTag("send-check")) {
                    Text(stringResource(R.string.send_check))
                }
            }
        } else {
            Block(stringResource(R.string.send_confirm_title)) {
                PlanFields(draft.plan, draft.to, if (draft.everything) draft.plan.payments[0].amountSat else draft.plan.sentSat)
                if (draft.against > 0) Text(stringResource(R.string.send_against_text), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("send-against"))
                if (draft.plan.leftBehind > 0) Text(stringResource(R.string.partial_note, draft.plan.inputs.size.toString(), draft.plan.leftBehind), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.send_confirm_text), style = MaterialTheme.typography.bodySmall)
                ProblemCard(state.sendProblem, "send-problem")
                if (state.sending) Busy(R.string.sending)
                Button(onClick = { model.confirmSend(owner) }, enabled = !state.sending, modifier = Modifier.fillMaxWidth().testTag("send-go")) { Text(stringResource(R.string.send_go)) }
                TextButton(onClick = model::dropDraft, enabled = !state.sending, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------- history

private class Line(val move: Movement, val own: Boolean)

@Composable
fun HistoryTab(state: WalletState) {
    val snapshot = state.snapshot
    if (snapshot == null) {
        Page { ProblemCard(state.problem); Text(stringResource(R.string.history_none)) }
        return
    }
    val lines = remember(snapshot) {
        (snapshot.moves.map { Line(it, true) } + snapshot.watchedMoves.map { Line(it, false) }).sortedWith(compareByDescending<Line> { it.move.height }.thenBy { it.move.txid })
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = screenPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (lines.isEmpty()) item { Text(stringResource(R.string.history_none)) }
        items(lines, key = { (if (it.own) "w" else "a") + it.move.txid }) { line ->
            val m = line.move
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(if (m.coinbase) R.string.output_reward else if (m.netSat >= 0) R.string.history_received else R.string.history_paid), style = MaterialTheme.typography.labelLarge)
                        Text(Format.signed(m.netSat), style = MaterialTheme.typography.bodyMedium.merge(figures), color = if (m.netSat >= 0) Sky.star else Sky.text)
                    }
                    Text(
                        Format.dateTime(m.time) + ", " + stringResource(R.string.output_where, m.height, snapshot.tip - m.height + 1) + ", " +
                            stringResource(if (line.own) R.string.history_wallet else R.string.history_watched),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    m.feeSat?.let { Text(stringResource(R.string.send_fee) + " " + Format.coins(it), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------- mining

@Composable
fun MiningTab(model: WalletModel, state: WalletState) {
    val snapshot = state.snapshot
    var hashrate by rememberSaveable(state.wallet?.hashrate) { mutableStateOf(state.wallet?.hashrate.orEmpty()) }
    Page {
        NoticeCard(model, state)
        if (snapshot == null) {
            ProblemCard(state.problem)
            Text(stringResource(R.string.history_none))
            return@Page
        }
        val rewards = (snapshot.moves + snapshot.watchedMoves).filter { it.coinbase }
        val dayAgo = snapshot.atMillis / 1000 - 86_400
        val lastDay = rewards.count { it.time >= dayAgo }
        Block(stringResource(R.string.mining_found)) {
            Field(stringResource(R.string.mining_blocks), rewards.size.toString(), "mining-blocks")
            Field(stringResource(R.string.mining_coins), Format.coins(rewards.sumOf { it.receivedSat }))
            Field(stringResource(R.string.mining_last_day), lastDay.toString())
            val waiting = snapshot.coins.filter { it.coinbase && !it.spendableAt(snapshot.tip) }.map { it.height } + (snapshot.watched?.immature?.map { it.height } ?: emptyList())
            if (waiting.isNotEmpty()) {
                val next = waiting.min() + Planner.MATURITY - snapshot.tip
                Text(stringResource(R.string.immature_note, waiting.size) + " " + pluralStringResource(R.plurals.next_matures, next, next), style = MaterialTheme.typography.bodySmall)
            }
        }
        Block(stringResource(R.string.mining_network)) {
            snapshot.hashps?.let { Field(stringResource(R.string.mining_hashrate), Format.hashrate(it)) }
            snapshot.secondsPerBlock?.let { Field(stringResource(R.string.mining_block_time), Format.decimal(it, 0) + " s") }
        }
        Block(stringResource(R.string.mining_mine)) {
            if (state.wallet == null) {
                Text(stringResource(R.string.mining_needs_wallet), style = MaterialTheme.typography.bodySmall)
            } else {
                OutlinedTextField(
                    value = hashrate, onValueChange = { hashrate = it }, singleLine = true, label = { Text(stringResource(R.string.mining_my_hashrate)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().testTag("my-hashrate"),
                )
                OutlinedButton(onClick = { model.saveMining(hashrate) }, modifier = Modifier.testTag("save-hashrate")) { Text(stringResource(R.string.save)) }
            }
            val mine = state.wallet?.hashrate?.replace(',', '.')?.toDoubleOrNull()
            val net = snapshot.hashps
            val seconds = snapshot.secondsPerBlock
            if (mine != null && mine > 0 && net != null && net > 0 && seconds != null && seconds > 0) {
                val share = (mine * 1000 / net).coerceAtMost(1.0)
                val perDay = share * 86_400 / seconds
                Field(stringResource(R.string.mining_share), Format.decimal(share * 100, 1) + " %", "mining-share")
                Field(stringResource(R.string.mining_expected), Format.decimal(perDay, 1))
                rewards.maxByOrNull { it.height }?.let { Field(stringResource(R.string.mining_expected_coins), Format.coins((perDay * it.receivedSat).toLong())) }
                if (perDay > 0) Field(stringResource(R.string.mining_luck), Format.decimal(lastDay / perDay * 100, 0) + " %")
                Text(stringResource(R.string.mining_note), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------- more

@Composable
fun MoreTab(model: WalletModel, state: WalletState, owner: Owner, go: (Section) -> Unit) {
    val context = LocalContext.current
    val wallet = state.wallet
    SecureWindow()                              // recovery words are typed on this page
    WordsOnScreen()
    // remember, not rememberSaveable: what is typed here must not be written into the saved state of the screen
    var restoring by remember { mutableStateOf("") }
    var askRemoveWallet by rememberSaveable { mutableStateOf(false) }
    var askForget by rememberSaveable { mutableStateOf(false) }
    var price by rememberSaveable(wallet?.price) { mutableStateOf(wallet?.price.orEmpty()) }
    var currency by rememberSaveable(wallet?.currency) { mutableStateOf(wallet?.currency.orEmpty()) }
    var notify by remember { mutableStateOf(Notifier.enabled(context)) }
    var notifyDenied by remember { mutableStateOf(false) }
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) Notifier.enable(context, true)
        notify = granted
        notifyDenied = !granted
    }
    Page {
        NoticeCard(model, state)
        if (wallet == null) {
            NoWallet(model, state, owner)
            if (!state.wordsWithoutWallet) Block(stringResource(R.string.restore_title)) {
                Text(stringResource(R.string.restore_text), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = restoring, onValueChange = { restoring = it }, label = { Text(stringResource(R.string.words_label)) }, keyboardOptions = secretKeyboard,
                    modifier = Modifier.fillMaxWidth().testTag("restore-words"), minLines = 3,
                )
                ProblemCard(state.walletProblem, "wallet-problem")
                Button(onClick = { model.restoreWallet(restoring, owner); restoring = "" }, enabled = restoring.isNotBlank(), modifier = Modifier.testTag("restore-go")) {
                    Text(stringResource(R.string.restore_go))
                }
            }
        } else {
            Block(stringResource(R.string.wallet_title)) {
                Text(stringResource(R.string.wallet_export_text), style = MaterialTheme.typography.bodySmall)
                val descriptor = remember(wallet.xpub) { wallet.watchKey().descriptor(0) }
                Mono(descriptor)
                OutlinedButton(onClick = { copyToClipboard(context, "descriptor", descriptor) }) { Text(stringResource(R.string.copy)) }
                Tick(wallet.lockOnOpen, { model.saveLock(it, owner) }, stringResource(R.string.lock_setting), "lock-setting")
                val looked = state.snapshot?.lookedTo ?: 0
                if (looked > 1) {
                    Text(stringResource(R.string.further_more_text, looked.toString()), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("looked-to"))
                    if (looked < WalletInfo.MAX_LOOK) {
                        OutlinedButton(onClick = model::lookFurther, modifier = Modifier.testTag("look-further-more")) { Text(stringResource(R.string.further_more_go)) }
                    }
                    Text(stringResource(R.string.renumber_text), style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = model::renumberKeys, modifier = Modifier.testTag("renumber")) { Text(stringResource(R.string.renumber_go)) }
                }
                OutlinedButton(onClick = { askRemoveWallet = true }, modifier = Modifier.testTag("wallet-remove")) { Text(stringResource(R.string.wallet_remove)) }
            }
            Block(stringResource(R.string.price_title)) {
                Text(stringResource(R.string.price_text), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = price, onValueChange = { price = it }, singleLine = true, label = { Text(stringResource(R.string.price_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().testTag("price"),
                )
                OutlinedTextField(
                    value = currency, onValueChange = { currency = it }, singleLine = true, label = { Text(stringResource(R.string.price_currency)) },
                    modifier = Modifier.fillMaxWidth().testTag("currency"),
                )
                ProblemCard(state.walletProblem?.takeIf { it.kind == Problem.Kind.BAD_PRICE }, "price-problem")
                OutlinedButton(onClick = { model.savePrice(price, currency) }, modifier = Modifier.testTag("save-price")) { Text(stringResource(R.string.save)) }
            }
        }
        // the mining page has no place of its own in the bar at the bottom; it is opened from here
        Block(stringResource(R.string.tab_mining)) {
            Text(stringResource(R.string.mining_more_text), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { go(Section.MINING) }, modifier = Modifier.testTag("tab-MINING")) { Text(stringResource(R.string.mining_more_go)) }
        }
        Block(stringResource(R.string.notify_title)) {
            Text(stringResource(R.string.notify_text), style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = notify,
                    onCheckedChange = { on ->
                        if (on && Build.VERSION.SDK_INT >= 33) {
                            askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            Notifier.enable(context, on)
                            notify = on
                            notifyDenied = false
                        }
                    },
                    modifier = Modifier.testTag("notify"),
                )
                Text(stringResource(R.string.notify_setting))
            }
            if (notifyDenied) Text(stringResource(R.string.notify_denied), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        PaperBlock(model, state)
        Block(stringResource(R.string.node_title)) {
            state.setup?.let { Field(stringResource(R.string.node_address), it.host) }
            OutlinedButton(onClick = { askForget = true }, modifier = Modifier.testTag("forget")) { Text(stringResource(R.string.forget)) }
        }
        SelfTestCard(model, state)
        Block(stringResource(R.string.about_title)) {
            Text(stringResource(R.string.about_text, appVersion(context)), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("about"))
            Text(stringResource(R.string.about_libraries), style = MaterialTheme.typography.bodySmall)
        }
    }
    if (askRemoveWallet) {
        AlertDialog(
            onDismissRequest = { askRemoveWallet = false },
            title = { Text(stringResource(R.string.wallet_remove_title)) },
            text = { Text(stringResource(R.string.wallet_remove_text)) },
            confirmButton = { TextButton(onClick = { askRemoveWallet = false; model.removeWallet(owner) }, modifier = Modifier.testTag("wallet-remove-yes")) { Text(stringResource(R.string.wallet_remove_yes)) } },
            dismissButton = { TextButton(onClick = { askRemoveWallet = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (askForget) {
        AlertDialog(
            onDismissRequest = { askForget = false },
            title = { Text(stringResource(R.string.forget_title)) },
            text = { Text(stringResource(R.string.forget_text)) },
            confirmButton = { TextButton(onClick = { askForget = false; model.forgetSetup(owner) }, modifier = Modifier.testTag("forget-yes")) { Text(stringResource(R.string.forget_yes)) } },
            dismissButton = { TextButton(onClick = { askForget = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/**
 * Emptying a paper address: its words are typed in for this one use and are not stored.
 *
 * What was worked out is kept with the addresses it was worked out for, and the confirmation shows those.
 * A change in any field throws the worked-out payment away, so that what is confirmed is always what is paid.
 */
@Composable
private fun PaperBlock(model: WalletModel, state: WalletState) {
    val wallet = state.wallet
    var words by remember { mutableStateOf("") }
    var keep by rememberSaveable { mutableStateOf("") }
    var restTo by rememberSaveable { mutableStateOf("") }
    var other by rememberSaveable { mutableStateOf("") }
    var rate by rememberSaveable { mutableStateOf(Planner.DEFAULT_FEE_RATE) }
    val mine = remember(wallet?.xpub, wallet?.receiveIndex) { wallet?.let { it.watchKey().address(0, it.receiveIndex) } }
    Block(stringResource(R.string.paper_title)) {
        Text(stringResource(R.string.paper_text), style = MaterialTheme.typography.bodySmall)
        state.paperTxid?.let { txid ->
            Text(stringResource(R.string.sent_text))
            Mono(txid, "paper-txid")
            if (state.paper == null && !state.paperBusy) TextButton(onClick = model::paperDrop) { Text(stringResource(R.string.ok)) }
        }
        val found = state.paper
        if (found == null) {
            OutlinedTextField(
                value = words, onValueChange = { words = it }, label = { Text(stringResource(R.string.words_label)) }, keyboardOptions = secretKeyboard,
                modifier = Modifier.fillMaxWidth().testTag("paper-words"), minLines = 3,
            )
            ProblemCard(state.paperProblem, "paper-problem")
            if (state.paperBusy) Busy(R.string.asking_node)
            Button(onClick = { model.paperLook(words); words = "" }, enabled = words.isNotBlank() && !state.paperBusy, modifier = Modifier.testTag("paper-look")) {
                Text(stringResource(R.string.paper_look))
            }
        } else {
            Mono(found.address)
            Field(stringResource(R.string.spendable), Format.coins(found.spendableSat), "paper-spendable")
            if (found.immatureSat > 0) Field(stringResource(R.string.immature), Format.coins(found.immatureSat))
            val to = mine ?: other
            if (mine != null) {
                Text(stringResource(R.string.paper_to_wallet), style = MaterialTheme.typography.labelMedium)
                Mono(mine)
            } else {
                OutlinedTextField(
                    value = other, onValueChange = { other = it.trim(); model.paperEdited() }, label = { Text(stringResource(R.string.send_to)) },
                    modifier = Modifier.fillMaxWidth().testTag("paper-to"), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
            OutlinedTextField(
                value = keep, onValueChange = { keep = it; model.paperEdited() }, singleLine = true, label = { Text(stringResource(R.string.paper_keep)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().testTag("paper-keep"),
            )
            OutlinedTextField(
                value = restTo, onValueChange = { restTo = it.trim(); model.paperEdited() }, label = { Text(stringResource(R.string.paper_rest_to)) },
                modifier = Modifier.fillMaxWidth().testTag("paper-rest"), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            FeeChips(rate) { rate = it; model.paperEdited() }
            ProblemCard(state.paperProblem, "paper-problem")
            val draft = state.paperDraft
            if (draft == null) {
                Button(onClick = { model.paperPrepare(to, keep, restTo, rate) }, enabled = !state.paperBusy, modifier = Modifier.fillMaxWidth().testTag("paper-check")) {
                    Text(stringResource(R.string.send_check))
                }
            } else {
                val plan = draft.plan
                Text(stringResource(R.string.send_confirm_title), style = MaterialTheme.typography.titleSmall)
                PlanFields(plan, draft.to, plan.payments[0].amountSat, showChange = false)
                if (plan.payments.size > 1 && draft.restTo != null) {
                    Text(stringResource(R.string.paper_rest_to), style = MaterialTheme.typography.labelMedium)
                    Mono(draft.restTo, "paper-rest-shown")
                    Field(stringResource(R.string.send_amount), Format.coins(plan.payments[1].amountSat))
                }
                val notes = ArrayList<String>()
                if (plan.leftBehind > 0) notes.add(stringResource(R.string.paper_warning_part, plan.inputs.size.toString(), plan.leftBehind))
                if (found.immatureSat > 0) notes.add(stringResource(R.string.paper_warning_rest, Format.coins(found.immatureSat), found.blocksUntilRipe))
                if (notes.isEmpty()) notes.add(stringResource(R.string.paper_warning))
                notes.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("paper-warning")) }
                Text(stringResource(R.string.send_confirm_text), style = MaterialTheme.typography.bodySmall)
                if (state.paperBusy) Busy(R.string.sending)
                Button(onClick = model::paperSend, enabled = !state.paperBusy, modifier = Modifier.fillMaxWidth().testTag("paper-go")) { Text(stringResource(R.string.paper_go)) }
            }
            TextButton(onClick = model::paperDrop, enabled = !state.paperBusy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
        }
    }
}
