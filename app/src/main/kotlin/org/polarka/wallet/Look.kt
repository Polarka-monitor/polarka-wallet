package org.polarka.wallet

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/*
 * The look of the app: a night sky and one bright star. The colours, the shapes, the few drawn icons and the
 * parts that give the screens their face are here. Nothing in this file decides anything about coins.
 */

object Sky {
    val night = Color(0xFF0A1020)       // the background
    val bar = Color(0xFF0C1326)         // the bar at the bottom
    val card = Color(0xFF131C31)
    val line = Color(0xFF1C2640)        // thin lines between rows
    val edge = Color(0xFF2A3550)        // borders of quiet things
    val text = Color(0xFFEEF2FA)
    val quiet = Color(0xFF9AA6BF)       // labels and notes
    val star = Color(0xFFF2C14E)        // the one accent
    val good = Color(0xFF6FD08C)
    val bad = Color(0xFFFF9B8F)
}

private val polaris = darkColorScheme(
    primary = Sky.star, onPrimary = Sky.night,
    primaryContainer = Color(0xFF3A2F12), onPrimaryContainer = Color(0xFFFFE3A1),
    secondary = Sky.quiet, onSecondary = Sky.night,
    secondaryContainer = Sky.edge, onSecondaryContainer = Sky.text,
    tertiary = Color(0xFF8FD3FF), onTertiary = Sky.night,
    tertiaryContainer = Sky.card, onTertiaryContainer = Sky.text,
    background = Sky.night, onBackground = Sky.text,
    surface = Sky.night, onSurface = Sky.text,
    surfaceVariant = Sky.line, onSurfaceVariant = Sky.quiet,
    surfaceTint = Sky.star,
    inverseSurface = Sky.text, inverseOnSurface = Sky.night, inversePrimary = Color(0xFF7A5C22),
    error = Sky.bad, onError = Color(0xFF3B0A06),
    errorContainer = Color(0xFF3B1820), onErrorContainer = Color(0xFFFFDAD4),
    // the border of a field one types into must be seen against the night; the quiet edge is for decoration only
    outline = Color(0xFF5B6885), outlineVariant = Sky.edge,
    scrim = Color.Black,
    surfaceBright = Sky.line, surfaceDim = Sky.night,
    surfaceContainerLowest = Color(0xFF070B17), surfaceContainerLow = Sky.bar, surfaceContainer = Color(0xFF101829),
    surfaceContainerHigh = Sky.card, surfaceContainerHighest = Sky.card,
)

private val rounded = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp),
)

/** The app is dark whatever the phone is set to: the look is one thing, not two. */
@Composable
fun PolarkaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = polaris, shapes = rounded, content = content)
}

/** Figures that stand under one another: every digit as wide as the next. */
val figures = TextStyle(fontFeatureSettings = "tnum")

/** The icons, drawn here as lines: no icon library is brought into the wallet for seven small pictures. */
object Drawn {
    private fun lines(vararg strokes: String): ImageVector {
        val b = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        for (s in strokes) {
            b.addPath(addPathNodes(s), stroke = SolidColor(Color.White), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
        }
        return b.build()
    }

    private fun filled(width: Float, vararg shapes: String): ImageVector {
        val b = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = width, viewportHeight = width)
        for (s in shapes) b.addPath(addPathNodes(s), fill = SolidColor(Color.White))
        return b.build()
    }

    /** The four-pointed star of the north, with drawn-in sides. */
    val star = filled(100f, "M50 4C54 34 66 46 96 50C66 54 54 66 50 96C46 66 34 54 4 50C34 46 46 34 50 4Z")
    val refresh = lines("M20 12a8 8 0 1 1-2.6-5.9", "M20 4v5h-5")
    val lock = lines("M7 11h10a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2z", "M8 11V8a4 4 0 0 1 8 0v3")
    val home = lines("M4 11L12 4l8 7", "M6 10v9h12v-9")
    val receive = lines("M17 7L7 17", "M7 9v8h8")
    val send = lines("M7 17L17 7", "M9 7h8v8")
    val clock = lines("M12 4a8 8 0 1 0 0 16a8 8 0 1 0 0-16z", "M12 8v4l3 2")
    val more = filled(
        24f, "M6 10.4a1.6 1.6 0 1 0 0 3.2a1.6 1.6 0 1 0 0-3.2z", "M12 10.4a1.6 1.6 0 1 0 0 3.2a1.6 1.6 0 1 0 0-3.2z",
        "M18 10.4a1.6 1.6 0 1 0 0 3.2a1.6 1.6 0 1 0 0-3.2z",
    )

    fun of(section: Section): ImageVector = when (section) {
        Section.OVERVIEW -> home
        Section.RECEIVE -> receive
        Section.SEND -> send
        Section.HISTORY -> clock
        Section.MINING, Section.MORE -> more
    }
}

/** The star and the name, at the top of every screen. */
@Composable
fun Brand() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Drawn.star, contentDescription = null, tint = Sky.star, modifier = Modifier.size(26.dp))
        Text(stringResource(R.string.brand), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.02.em)
    }
}

/**
 * A balance in large, light figures: the whole coins bright, the fraction smaller and quieter, the unit in
 * the colour of the star. It is one text, so that a screen reader and a test read it as one amount.
 */
@Composable
fun BigAmount(coins: String, tag: String) {
    val whole = coins.substringBefore('.')
    val fraction = coins.substring(whole.length)
    val size = if (whole.length <= 5) 44 else if (whole.length <= 7) 36 else 28
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontSize = size.sp, fontWeight = FontWeight.Light, color = Sky.text)) { append(whole) }
            withStyle(SpanStyle(fontSize = (size / 2).sp, fontWeight = FontWeight.Light, color = Sky.quiet)) { append(fraction) }
            withStyle(SpanStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Sky.star)) { append(" NORTH") }
        },
        maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
        style = figures.copy(lineHeight = (size * 1.25f).sp), modifier = Modifier.testTag(tag),
    )
}

/** The two things done most often, right under the balance. */
@Composable
fun ReceiveAndSend(onReceive: () -> Unit, onSend: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val inside = PaddingValues(horizontal = 12.dp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onReceive, modifier = Modifier.weight(1f).height(52.dp).testTag("go-receive"), shape = shape, contentPadding = inside) {
            Icon(Drawn.receive, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.tab_receive), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        OutlinedButton(
            onClick = onSend, modifier = Modifier.weight(1f).height(52.dp).testTag("go-send"), shape = shape, contentPadding = inside,
            border = BorderStroke(1.dp, Sky.edge), colors = ButtonDefaults.outlinedButtonColors(containerColor = Sky.card, contentColor = Sky.text),
        ) {
            Icon(Drawn.send, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.tab_send), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** The pages of the app, at the bottom of the screen. Mining is reached from More and counts as part of it here. */
@Composable
fun BottomBar(section: Section, go: (Section) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Sky.bar)) {
        HorizontalDivider(color = Sky.line)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp)) {
            for (s in listOf(Section.OVERVIEW, Section.RECEIVE, Section.SEND, Section.HISTORY, Section.MORE)) {
                val here = s == section || (s == Section.MORE && section == Section.MINING)
                val colour = if (here) Sky.star else Sky.quiet
                Column(
                    Modifier.weight(1f).heightIn(min = 52.dp).clip(RoundedCornerShape(12.dp))
                        .selectable(selected = here, role = Role.Tab, onClick = { go(s) }).testTag("tab-" + s.name),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Icon(Drawn.of(s), contentDescription = null, tint = colour, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(s.title), fontSize = 11.sp, fontWeight = if (here) FontWeight.SemiBold else FontWeight.Normal, color = colour, maxLines = 1)
                }
            }
        }
    }
}

/** A small round light before a line of text: the node answers. */
@Composable
fun Lit(text: String, tag: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(Sky.good))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Sky.quiet, modifier = if (tag == null) Modifier else Modifier.testTag(tag))
    }
}
