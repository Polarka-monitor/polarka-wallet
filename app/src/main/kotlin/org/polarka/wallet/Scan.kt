package org.polarka.wallet

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import north.core.Chain
import north.core.Qrh
import java.util.concurrent.Executors

object Scan {
    /**
     * The address in a scanned or pasted text, or null if there is none for this network. A code may hold the
     * bare address or a payment link (a name, a colon, the address, then optional parameters); only the address
     * is taken, and only if it passes the same checks as a typed one.
     */
    fun address(text: String, chain: Chain): String? {
        val candidates = listOf(text.trim(), text.trim().substringAfter(':').substringBefore('?').trim())
        return candidates.firstOrNull { c -> c.isNotEmpty() && runCatching { Qrh.scriptForAddress(chain, c) }.isSuccess }
    }

    /** Looks for a QR code in one camera picture; only the brightness plane is read. */
    fun decode(image: ImageProxy): String? = try {
        val plane = image.planes[0]
        val bytes = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
        val source = PlanarYUVLuminanceSource(bytes, plane.rowStride, image.height, 0, 0, image.width, image.height, false)
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)).text
    } catch (e: Exception) {
        null                // no code in this picture
    }
}

/**
 * The camera picture with a reader of QR codes behind it. Pictures are looked at in memory and thrown away;
 * nothing is saved or sent anywhere. [onText] gets every text that is read.
 */
@Composable
fun QrScanner(onText: (String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val found = rememberUpdatedState(onText)
    val worker = remember { Executors.newSingleThreadExecutor() }
    val cameras = remember { ProcessCameraProvider.getInstance(context) }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { cameras.get().unbindAll() }
            worker.shutdown()
        }
    }
    AndroidView(
        modifier = modifier.fillMaxWidth().aspectRatio(1f),
        factory = { ctx ->
            val view = PreviewView(ctx)
            cameras.addListener({
                runCatching {
                    val provider = cameras.get()
                    val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                    val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    analysis.setAnalyzer(worker) { image ->
                        val text = Scan.decode(image)
                        image.close()
                        if (text != null) ctx.mainExecutor.execute { found.value(text) }
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
            }, ctx.mainExecutor)
            view
        },
    )
}
