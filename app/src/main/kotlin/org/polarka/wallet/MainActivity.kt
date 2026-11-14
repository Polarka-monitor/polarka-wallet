package org.polarka.wallet

import android.content.ClipboardManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    internal val model: WalletModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // a released build never lets its screen be captured; a test build does, except where words are shown
        if (!isDebugBuild(this)) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        // no other app may draw over this one: a window laid over a confirmation could show another address than the real one
        runCatching { window.setHideOverlayWindows(true) }
        // Android may build this screen anew while the app is in the background (the phone was turned, folded,
        // switched to dark mode). The time away is kept in the model, which survives that, and is looked at here too.
        model.returned()
        // the app is dark whatever the phone is set to, so the signs in the system's bars are always the light ones
        val clear = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = clear, navigationBarStyle = clear)
        runCatching { Notifier.schedule(this) }          // never a reason for the app not to start
        val owner = testOwner ?: PhoneOwner(this, getString(R.string.unlock_title), getString(R.string.unlock_subtitle))
        setContent { WalletApp(model, owner) }
    }

    override fun onStop() {
        super.onStop()
        model.left()
    }

    override fun onStart() {
        super.onStart()
        model.returned()
    }

    override fun onResume() {
        super.onResume()
        model.refreshIfStale()
    }

    companion object {
        /** A test puts its own owner here; the app itself always asks the phone. */
        @Volatile
        var testOwner: Owner? = null
    }
}

enum class Section(val title: Int) {
    OVERVIEW(R.string.tab_overview), RECEIVE(R.string.tab_receive), SEND(R.string.tab_send),
    HISTORY(R.string.tab_history), MINING(R.string.tab_mining), MORE(R.string.tab_more),
}

/** The public node offered on the first screen. The user may type another one. */
const val PUBLIC_NODE = "https://node.polarka.org"

val screenPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)
val gap = Arrangement.spacedBy(12.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletApp(model: WalletModel, owner: Owner) {
    PolarkaTheme {
        val state = model.state
        var section by rememberSaveable { mutableStateOf(Section.OVERVIEW) }
        val inside = state.loaded && !state.locked && state.setup != null && state.newWords == null
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Brand() },
                    actions = {
                        if (inside) {
                            IconButton(onClick = model::refresh, enabled = !state.busy, modifier = Modifier.testTag("refresh")) {
                                Icon(Drawn.refresh, contentDescription = stringResource(R.string.refresh))
                            }
                            // locking by hand is offered where the app locks at all
                            if (state.wallet?.lockOnOpen == true) {
                                IconButton(onClick = model::lockNow, modifier = Modifier.testTag("lock-now")) {
                                    Icon(Drawn.lock, contentDescription = stringResource(R.string.lock_now))
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Sky.night, scrolledContainerColor = Sky.night, actionIconContentColor = Sky.quiet),
                )
            },
            bottomBar = { if (inside) BottomBar(section) { section = it } },
        ) { inner ->
            Column(Modifier.padding(inner).fillMaxSize()) {
                when {
                    !state.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    state.locked -> LockScreen(model, owner)
                    state.setup == null -> SetupScreen(model, state)
                    state.newWords != null -> NewWalletScreen(model, state, owner)
                    else -> when (section) {
                        Section.OVERVIEW -> OverviewTab(model, state, owner) { section = it }
                        Section.RECEIVE -> ReceiveTab(model, state, owner)
                        Section.SEND -> SendTab(model, state, owner)
                        Section.HISTORY -> HistoryTab(state)
                        Section.MINING -> MiningTab(model, state)
                        Section.MORE -> MoreTab(model, state, owner) { section = it }
                    }
                }
            }
        }
    }
}

@Composable
private fun LockScreen(model: WalletModel, owner: Owner) {
    // the phone's dialog comes up by itself, except when the owner has just locked the app by hand
    LaunchedEffect(Unit) { if (!model.state.byHand) model.unlockApp(owner) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Drawn.star, contentDescription = null, tint = Sky.star, modifier = Modifier.size(56.dp))
        Text(stringResource(R.string.lock_text), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 20.dp))
        if (model.state.lockNote) {
            Text(stringResource(R.string.lock_hint), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp).testTag("lock-hint"))
        }
        Button(onClick = { model.unlockApp(owner) }, modifier = Modifier.padding(top = 16.dp).testTag("unlock")) { Text(stringResource(R.string.lock_open)) }
    }
}

// ---------------------------------------------------------------------------------------------- node setup

@Composable
private fun SetupScreen(model: WalletModel, state: WalletState) {
    val context = LocalContext.current
    var deleteFile by rememberSaveable { mutableStateOf(true) }
    var publicUrl by rememberSaveable { mutableStateOf(PUBLIC_NODE) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) model.offerFile(uri) }

    val scroll = rememberScrollState()
    // what was read from a file or typed in is shown from its top: "Check before saving" must not begin above the visible part
    LaunchedEffect(state.offered) { if (state.offered != null) scroll.scrollTo(0) }
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(screenPadding), verticalArrangement = gap) {
        Banner()
        NoticeCard(model, state)
        ProblemCard(state.problem)
        val offered = state.offered
        if (offered == null) {
            state.offerError?.let { reason ->
                Text(setupErrorText(reason, state.offerDetail), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("setup-error"))
            }
            Block(stringResource(R.string.public_title)) {
                Text(stringResource(R.string.public_intro))
                Text(stringResource(R.string.public_sees), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = publicUrl, onValueChange = { publicUrl = it.trim() }, singleLine = true, label = { Text(stringResource(R.string.public_address)) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().testTag("public-url"),
                )
                Button(onClick = { model.offerPublic(publicUrl) }, enabled = publicUrl.isNotBlank(), modifier = Modifier.fillMaxWidth().testTag("public-go")) {
                    Text(stringResource(R.string.public_go))
                }
            }
            Block(stringResource(R.string.setup_title)) {
                Text(stringResource(R.string.setup_intro))
                Text(stringResource(R.string.setup_how))
                // every file type: phones do not agree on what kind of file a .json is, and the content is checked anyway
                Button(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_pick_file)) }
                OutlinedButton(
                    onClick = {
                        val text = readClipboard(context)
                        if (text.isNullOrBlank()) {
                            model.note(Notice.CLIPBOARD_EMPTY)
                        } else if (model.offer(text)) {
                            // the clipboard held the password in plain text; other apps can read the clipboard
                            runCatching { context.getSystemService(ClipboardManager::class.java).clearPrimaryClip() }
                                .onSuccess { model.note(Notice.CLIPBOARD_CLEARED) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().testTag("paste"),
                ) { Text(stringResource(R.string.setup_paste)) }
            }
        } else {
            Block(stringResource(R.string.offer_title)) {
                Field(stringResource(R.string.offer_node), offered.url)
                if (offered.isPublic) {
                    Field(stringResource(R.string.offer_user), stringResource(R.string.offer_no_account))
                } else {
                    Field(stringResource(R.string.offer_user), offered.user)
                    Field(stringResource(R.string.offer_password), stringResource(R.string.offer_password_set, offered.password.length))
                }
                Field(stringResource(R.string.offer_chain), offered.chainName)
                if (offered.addresses.isNotEmpty()) {
                    Text(stringResource(R.string.offer_addresses), style = MaterialTheme.typography.labelMedium)
                    offered.addresses.forEach { Mono(it) }
                }
                Text(
                    stringResource(if (offered.isPublic) R.string.public_sees else R.string.offer_own_sees),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("offer-sees"),
                )
                if (state.offeredFrom != null && !offered.isPublic) {
                    Tick(deleteFile, { deleteFile = it }, stringResource(R.string.offer_delete_file))
                }
                Button(onClick = { model.acceptOffer(deleteFile && !offered.isPublic) }, modifier = Modifier.fillMaxWidth().testTag("save")) { Text(stringResource(R.string.offer_save)) }
                TextButton(onClick = model::dropOffer, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
            }
        }
        SelfTestCard(model, state)
    }
}

@Composable
private fun setupErrorText(reason: SetupError.Reason, detail: String): String = when (reason) {
    SetupError.Reason.TOO_LONG -> stringResource(R.string.err_setup_too_long)
    SetupError.Reason.NOT_JSON -> stringResource(R.string.err_setup_not_json)
    SetupError.Reason.WRONG_FORMAT -> stringResource(R.string.err_setup_format, detail)
    SetupError.Reason.BAD_URL -> stringResource(R.string.err_setup_url)
    SetupError.Reason.NOT_HTTPS -> stringResource(R.string.err_setup_https)
    SetupError.Reason.BAD_USER -> stringResource(R.string.err_setup_user)
    SetupError.Reason.BAD_PASSWORD -> stringResource(R.string.err_setup_password)
    SetupError.Reason.BAD_CHAIN -> stringResource(R.string.err_setup_chain, detail)
    SetupError.Reason.NO_ADDRESS -> stringResource(R.string.err_setup_no_address)
    SetupError.Reason.TOO_MANY_ADDRESSES -> stringResource(R.string.err_setup_too_many)
    SetupError.Reason.BAD_ADDRESS -> stringResource(R.string.err_setup_address, detail)
}

// ---------------------------------------------------------------------------------------------- a new wallet

/** Keyboards learn what is typed. For recovery words that must not happen: no suggestions, no learning. */
val secretKeyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password)

@Composable
private fun NewWalletScreen(model: WalletModel, state: WalletState, owner: Owner) {
    val words = state.newWords?.words ?: return
    SecureWindow()
    WordsOnScreen()
    var written by rememberSaveable { mutableStateOf(false) }
    var checking by rememberSaveable { mutableStateOf(false) }
    val asked = remember(words) { words.indices.shuffled().take(3).sorted() }
    val typed = remember(words) { asked.map { mutableStateOf("") } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(screenPadding), verticalArrangement = gap) {
        if (!checking) {
            Block(stringResource(R.string.new_title)) {
                Text(stringResource(R.string.new_intro))
                for (row in 0 until words.size / 2) {
                    Row(Modifier.fillMaxWidth()) {
                        for (i in listOf(row, row + words.size / 2)) {
                            Text("%2d. %s".format(i + 1, words[i]), fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f).testTag("word-$i"))
                        }
                    }
                }
                Text(stringResource(R.string.new_warning), color = MaterialTheme.colorScheme.error)
                Tick(written, { written = it }, stringResource(R.string.new_written), "written")
                Button(onClick = { checking = true }, enabled = written, modifier = Modifier.fillMaxWidth().testTag("new-next")) { Text(stringResource(R.string.new_next)) }
                TextButton(onClick = model::cancelNewWallet, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
            }
        } else {
            Block(stringResource(R.string.check_title)) {
                Text(stringResource(R.string.check_intro))
                asked.forEachIndexed { k, position ->
                    OutlinedTextField(
                        value = typed[k].value, onValueChange = { typed[k].value = it.trim().lowercase() }, singleLine = true,
                        label = { Text(stringResource(R.string.check_word, position + 1)) }, keyboardOptions = secretKeyboard,
                        modifier = Modifier.fillMaxWidth().testTag("check-$k"),
                    )
                }
                val right = asked.indices.all { typed[it].value == words[asked[it]] }
                ProblemCard(state.walletProblem, "wallet-problem")
                Button(onClick = { model.confirmNewWallet(owner) }, enabled = right, modifier = Modifier.fillMaxWidth().testTag("new-save")) {
                    Text(stringResource(R.string.check_save))
                }
                TextButton(onClick = { checking = false }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.check_back)) }
            }
        }
    }
}

@Composable
fun SelfTestCard(model: WalletModel, state: WalletState) {
    Block(stringResource(R.string.selftest_title)) {
        Text(stringResource(R.string.selftest_text), style = MaterialTheme.typography.bodySmall)
        when (state.selfTest) {
            SelfTest.NOT_RUN -> Unit
            SelfTest.RUNNING -> Text(stringResource(R.string.selftest_running))
            SelfTest.PASSED -> Text(stringResource(R.string.selftest_passed), modifier = Modifier.testTag("selftest-result"))
            SelfTest.FAILED -> Text(
                stringResource(R.string.selftest_failed, state.selfTestDetail), color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("selftest-result"),
            )
        }
        OutlinedButton(onClick = model::runSelfTest, enabled = state.selfTest != SelfTest.RUNNING, modifier = Modifier.testTag("selftest")) {
            Text(stringResource(R.string.selftest_run))
        }
    }
}
