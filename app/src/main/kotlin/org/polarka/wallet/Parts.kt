package org.polarka.wallet

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.crypto.Cipher
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The owner asked through the phone's own unlock dialog: a fingerprint, or the screen lock as a fallback. */
class PhoneOwner(private val activity: Activity, private val title: String, private val subtitle: String) : Owner {
    private val allowed = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    override suspend fun unlock(cipher: Cipher): Cipher = ask(BiometricPrompt.CryptoObject(cipher))?.cipher ?: cipher

    override suspend fun present(): Boolean = try {
        ask(null)
        true
    } catch (e: OwnerRefused) {
        false
    }

    private suspend fun ask(crypto: BiometricPrompt.CryptoObject?): BiometricPrompt.CryptoObject? = suspendCancellableCoroutine { waiting ->
        val cancel = CancellationSignal()
        waiting.invokeOnCancellation { cancel.cancel() }
        val prompt = BiometricPrompt.Builder(activity).setTitle(title).setSubtitle(subtitle).setAllowedAuthenticators(allowed).build()
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                if (waiting.isActive) waiting.resume(result.cryptoObject)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (waiting.isActive) waiting.resumeWithException(OwnerRefused(errString.toString()))
            }
        }
        try {
            if (crypto != null) prompt.authenticate(crypto, cancel, activity.mainExecutor, callback)
            else prompt.authenticate(cancel, activity.mainExecutor, callback)
        } catch (e: Exception) {
            if (waiting.isActive) waiting.resumeWithException(OwnerRefused(e.javaClass.simpleName))
        }
    }
}

/** The version of the app as the phone knows it. */
fun appVersion(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"

fun isDebugBuild(context: Context): Boolean = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

/** While this is on the screen, the screen cannot be captured and is hidden in the list of recent apps. */
@Composable
fun SecureWindow() {
    val activity = LocalActivity.current
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            // a released build keeps the whole app secure (see MainActivity); a test build frees the other screens again
            if (activity != null && isDebugBuild(activity)) activity.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

/**
 * While this is on the screen, recovery words are shown or typed there. From Android 14 on, apps with
 * accessibility rights that are not screen readers are then not given what the screen holds.
 */
@Composable
fun WordsOnScreen() {
    val view = LocalView.current
    DisposableEffect(view) {
        if (Build.VERSION.SDK_INT >= 34) view.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_YES)
        onDispose {
            if (Build.VERSION.SDK_INT >= 34) view.setAccessibilityDataSensitive(View.ACCESSIBILITY_DATA_SENSITIVE_AUTO)
        }
    }
}

/** A box to tick with its text; a tap anywhere on the line counts. */
@Composable
fun Tick(checked: Boolean, onChange: (Boolean) -> Unit, text: String, tag: String? = null) {
    Row(
        (if (tag == null) Modifier else Modifier.testTag(tag)).fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(text, modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp))
    }
}

fun copyToClipboard(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
}

fun readClipboard(context: Context): String? =
    context.getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()

@Composable
fun Banner() {
    // a quiet frame, not a card: it is read once and should not compete with the balance
    Column(
        Modifier.fillMaxWidth().border(1.dp, Sky.edge, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(stringResource(R.string.banner_title, appVersion(LocalContext.current)), style = MaterialTheme.typography.labelLarge)
        Text(stringResource(R.string.banner_text), style = MaterialTheme.typography.bodySmall, color = Sky.quiet)
    }
}

/**
 * True while the setup names a wallet server. The pages then speak of the server where they would speak of
 * the node: it is the server that is asked, that may be busy, and that takes a payment.
 */
val LocalOverServer = staticCompositionLocalOf { false }

/** The sentence for a node, or the one for a wallet server while the app asks one. */
@Composable
@ReadOnlyComposable
fun pick(node: Int, server: Int): Int = if (LocalOverServer.current) server else node

@Composable
fun NoticeCard(model: WalletModel, state: WalletState) {
    val notice = state.notice ?: return
    val text = when (notice) {
        Notice.FILE_DELETED -> R.string.notice_file_deleted
        Notice.FILE_NOT_DELETED -> R.string.notice_file_not_deleted
        Notice.CLIPBOARD_CLEARED -> R.string.notice_clipboard_cleared
        Notice.FILE_UNREADABLE -> R.string.notice_file_unreadable
        Notice.CLIPBOARD_EMPTY -> R.string.notice_clipboard_empty
        Notice.WALLET_READY -> R.string.notice_wallet_ready
        Notice.WALLET_MADE -> R.string.notice_wallet_made
        Notice.WALLET_REMOVED -> R.string.notice_wallet_removed
        Notice.SAVED -> R.string.notice_saved
        Notice.PAYMENT_GONE -> pick(R.string.notice_payment_gone, R.string.notice_payment_gone_server)
        Notice.ADDRESS_LIMIT -> R.string.notice_address_limit
        Notice.WORDS_REMOVED -> R.string.notice_words_removed
        Notice.LOOK_LIMIT -> R.string.notice_look_limit
        Notice.PAYMENT_BEATEN -> R.string.notice_payment_beaten
        Notice.PAYMENT_ARRIVED -> R.string.notice_payment_arrived
        Notice.PAYMENT_BACK -> pick(R.string.notice_payment_back, R.string.notice_payment_back_server)
    }
    val withAmount = notice == Notice.PAYMENT_GONE || notice == Notice.PAYMENT_BEATEN || notice == Notice.PAYMENT_ARRIVED || notice == Notice.PAYMENT_BACK
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (withAmount) stringResource(text, state.noticeDetail) else stringResource(text),
                modifier = Modifier.weight(1f).testTag("notice"), style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { model.note(null) }) { Text(stringResource(R.string.ok)) }
        }
    }
}

@Composable
fun problemText(problem: Problem): String = when (problem.kind) {
    Problem.Kind.UNREACHABLE -> stringResource(pick(R.string.problem_unreachable, R.string.problem_unreachable_server))
    Problem.Kind.TIMEOUT -> stringResource(pick(R.string.problem_timeout, R.string.problem_timeout_server))
    Problem.Kind.BUSY -> stringResource(pick(R.string.problem_busy, R.string.problem_busy_server))
    Problem.Kind.LOGIN -> stringResource(R.string.problem_login)
    Problem.Kind.NOT_ALLOWED -> stringResource(R.string.problem_not_allowed)
    Problem.Kind.NODE -> stringResource(pick(R.string.problem_node, R.string.problem_node_server), problem.detail)
    Problem.Kind.OTHER_CHAIN -> stringResource(pick(R.string.problem_other_chain, R.string.problem_other_chain_server), problem.detail)
    Problem.Kind.FOREIGN_OUTPUTS -> stringResource(pick(R.string.problem_foreign, R.string.problem_foreign_server))
    Problem.Kind.STORE -> stringResource(pick(R.string.problem_store, R.string.problem_store_server), problem.detail)
    Problem.Kind.UNEXPECTED -> stringResource(R.string.problem_unexpected, problem.detail)
    Problem.Kind.BAD_ADDRESS -> stringResource(R.string.problem_bad_address)
    Problem.Kind.BAD_AMOUNT -> stringResource(R.string.problem_bad_amount)
    Problem.Kind.NOTHING_TO_SPEND -> stringResource(R.string.problem_nothing_to_spend)
    Problem.Kind.NOT_ENOUGH -> stringResource(R.string.problem_not_enough, problem.detail)
    Problem.Kind.FEE_RATE -> stringResource(R.string.problem_fee_rate)
    Problem.Kind.TOO_MANY_INPUTS -> stringResource(R.string.problem_too_many_inputs)
    Problem.Kind.WRONG_WORDS -> stringResource(R.string.problem_wrong_words)
    Problem.Kind.BAD_WORDS -> wordsProblemText(problem.detail)
    Problem.Kind.REJECTED -> stringResource(pick(R.string.problem_rejected, R.string.problem_rejected_server), problem.detail)
    Problem.Kind.UNLOCK -> stringResource(R.string.problem_unlock, problem.detail)
    Problem.Kind.IN_WAITING_PAYMENT -> stringResource(R.string.problem_in_waiting)
    Problem.Kind.UNSURE -> stringResource(pick(R.string.problem_unsure, R.string.problem_unsure_server), problem.detail)
    Problem.Kind.KEY_GONE -> stringResource(R.string.problem_key_gone)
    Problem.Kind.WORDS_EXIST -> stringResource(R.string.problem_words_exist)
    Problem.Kind.WALLET_FILE -> stringResource(R.string.problem_wallet_file, problem.detail)
    Problem.Kind.BAD_PRICE -> stringResource(R.string.problem_bad_price)
    Problem.Kind.TOO_MANY_WAITING -> stringResource(R.string.problem_too_many_waiting)
    Problem.Kind.EARLIER_PAYMENT -> stringResource(R.string.problem_earlier_payment)
    Problem.Kind.OUTCOME_OPEN -> stringResource(R.string.problem_outcome_open)
    Problem.Kind.REFUSED_KEPT -> stringResource(pick(R.string.problem_refused_kept, R.string.problem_refused_kept_server), problem.detail)
    Problem.Kind.TOO_MANY_COINS -> stringResource(R.string.problem_too_many_coins, counted(problem.detail))
    Problem.Kind.REFUSED_NO_ROOM -> stringResource(pick(R.string.problem_refused_no_room, R.string.problem_refused_no_room_server), problem.detail, WalletInfo.MAX_DROPPED)
    Problem.Kind.NO_ROOM -> stringResource(R.string.problem_no_room, WalletInfo.MAX_DROPPED)
    Problem.Kind.PAPER_REFUSED -> stringResource(pick(R.string.problem_paper_refused, R.string.problem_paper_refused_server), problem.detail)
    Problem.Kind.MOVING -> stringResource(R.string.problem_moving_server)
}

/** A count as the reader's language writes it, with its thousands apart. What is no number stays as it was. */
private fun counted(number: String): String =
    number.toLongOrNull()?.let { String.format(java.util.Locale.getDefault(), "%,d", it) } ?: number

/** What is wrong with typed recovery words. The model passes it on in a short form (WalletModel.wordsDetail). */
@Composable
private fun wordsProblemText(detail: String): String = when {
    detail.startsWith("list:") -> stringResource(R.string.problem_words_list, detail.substringAfter(':'))
    detail.startsWith("count:") -> stringResource(R.string.problem_words_count, detail.substringAfter(':'))
    detail == "fit" -> stringResource(R.string.problem_words_fit)
    else -> stringResource(R.string.problem_bad_words)
}

@Composable
fun ProblemCard(problem: Problem?, tag: String = "problem") {
    if (problem == null) return
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Text(problemText(problem), Modifier.padding(16.dp).testTag(tag), color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable
fun Warning(text: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Text(text, Modifier.padding(16.dp).testTag("warning"), color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable
fun Block(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Sky.quiet)
            content()
        }
    }
}

@Composable
fun Field(label: String, value: String, tag: String? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Sky.quiet, modifier = Modifier.padding(end = 12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium.merge(figures), modifier = if (tag == null) Modifier else Modifier.testTag(tag))
    }
}

@Composable
fun Mono(text: String, tag: String? = null) {
    Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = if (tag == null) Modifier else Modifier.testTag(tag))
}

/** A QR code drawn as black squares on white, with the quiet border the standard asks for. */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 4))
    }
    Box(modifier.aspectRatio(1f).background(Color.White)) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            val cell = size.width / matrix.width
            for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                if (matrix.get(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}
