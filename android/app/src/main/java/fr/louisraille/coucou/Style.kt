// The look of the iPhone app, for Compose: iOS's dark system colours and text
// styles, the cards and pills of AppleStyle.swift, and the pieces every screen
// shares (top bar, grouped lists, Deny / Allow).
//
// The iPhone app uses Liquid Glass on iOS 26 and a dark fill before it. Android
// has no such material, so this is the "before" look, exactly as the iPhone app
// draws it on iOS 18.

package fr.louisraille.coucou

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ── Colours (iOS, dark) ───────────────────────────────────────────────────────

object Ios {
    val orange = Color(0xFFFF9F0A)
    val cyan = Color(0xFF64D2FF)
    val red = Color(0xFFFF453A)
    val green = Color(0xFF30D158)
    val blue = Color(0xFF0A84FF)
    val yellow = Color(0xFFFFD60A)
    val purple = Color(0xFFBF5AF2)
    val pink = Color(0xFFFF375F)
    val indigo = Color(0xFF5E5CE6)
    val teal = Color(0xFF40C8E0)
    val gray = Color(0xFF8E8E93)

    /** .primary, .secondary, .tertiary, .quaternary label colours. */
    val label = Color.White
    val secondary = Color(0x99EBEBF5)
    val tertiary = Color(0x4DEBEBF5)
    val quaternary = Color(0x2EEBEBF5)
    val separator = Color(0xFF38383A)

    /** Color.accentColor. */
    val accent = blue

    /** Row background of an inset grouped list, on black. */
    val groupedCell = Color(0xFF1C1C1E)

    /** Color(white:). */
    fun white(w: Float) = Color(w, w, w)
}

/** "#RRGGBB" → Color; anything unreadable is white. */
fun colorFromHex(hex: String): Color = Color(hex(hex))

/**
 * Background for a white Mochi on an agent's colour. Very light colours (VS
 * Code's is near-white) would hide him, so they get a dark grey tile.
 */
fun mochiTile(hex: String): Color {
    val c = colorFromHex(hex)
    val luminance = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
    return if (luminance > 0.7f) Ios.white(0.32f) else c
}

// ── Text styles (SwiftUI's, at the default size) ──────────────────────────────

object T {
    val largeTitle = TextStyle(fontSize = 34.sp, lineHeight = 41.sp, color = Ios.label)
    val title = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, color = Ios.label)
    val title2 = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, color = Ios.label)
    val title3 = TextStyle(fontSize = 20.sp, lineHeight = 25.sp, color = Ios.label)
    val headline = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = Ios.label)
    val body = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, color = Ios.label)
    val callout = TextStyle(fontSize = 16.sp, lineHeight = 21.sp, color = Ios.label)
    val subheadline = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, color = Ios.label)
    val footnote = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, color = Ios.label)
    val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, color = Ios.label)
    val caption2 = TextStyle(fontSize = 11.sp, lineHeight = 13.sp, color = Ios.label)
}

/** .weight(.semibold) and friends. */
fun TextStyle.weight(w: FontWeight) = copy(fontWeight = w)
val TextStyle.semibold get() = copy(fontWeight = FontWeight.SemiBold)
val TextStyle.bold get() = copy(fontWeight = FontWeight.Bold)

/** .monospaced(). */
val TextStyle.mono get() = copy(fontFamily = FontFamily.Monospace)

/** .foregroundStyle(). */
fun TextStyle.color(c: Color) = copy(color = c)

// ── Cards, pills, presses ─────────────────────────────────────────────────────

/**
 * A card: the dark fill the iPhone app uses where there is no glass. There, a
 * tint only colours the glass of iOS 26; it is taken here so a card is written
 * as on the iPhone, and left out as on iOS 18.
 */
@Suppress("UNUSED_PARAMETER")
fun Modifier.glassCard(cornerRadius: Dp = 22.dp, tint: Color? = null): Modifier =
    background(Ios.white(0.11f), RoundedCornerShape(cornerRadius))

/** A small shape for pills and fields. */
fun Modifier.glassPill(shape: Shape = CircleShape, tint: Color? = null): Modifier =
    background(tint?.copy(alpha = 0.25f) ?: Ios.white(0.16f), shape)

/**
 * A tap with the press of PressableButtonStyle: shrinks a little under the
 * finger, like system buttons. No ripple.
 */
@Composable
fun Modifier.pressable(enabled: Boolean = true, role: Role = Role.Button, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, spring(dampingRatio = 0.7f, stiffness = 600f), label = "press")
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            alpha = if (pressed) 0.85f else 1f
        }
        .clickable(interactionSource = source, indication = null, enabled = enabled, role = role, onClick = onClick)
}

// ── Screen furniture ──────────────────────────────────────────────────────────

/** A round button of the navigation bar (back, share, settings). */
@Composable
fun BarButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).glassPill().pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, Modifier.size(22.dp), tint = Ios.label)
    }
}

/**
 * The navigation bar: a back button when the screen was pushed, the title in
 * the middle, the screen's own buttons on the right.
 */
@Composable
fun TopBar(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Box(Modifier.fillMaxWidth().statusBarsPadding().height(52.dp).padding(horizontal = 16.dp)) {
        if (onBack != null) {
            Box(Modifier.align(Alignment.CenterStart)) { BarButton(Sym.chevronLeft, "Back", onBack) }
        }
        Text(
            title, style = T.headline, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp),
        )
        Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
    }
}

/** The large title a tab opens on ("Services", "History"). */
@Composable
fun LargeTitle(text: String) {
    Text(text, style = T.largeTitle.bold, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp))
}

/** A section of an inset grouped list: its header, then the rows in one rounded block. */
@Composable
fun GroupedSection(
    header: String? = null, footer: String? = null, modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (header != null) {
            Text(
                header.uppercase(), style = T.footnote.color(Ios.secondary),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 7.dp),
            )
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Ios.groupedCell), content = content)
        if (footer != null) {
            Text(
                footer, style = T.footnote.color(Ios.secondary),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp),
            )
        }
    }
}

/** The hairline between two rows of a grouped section. */
@Composable
fun RowSeparator(inset: Dp = 16.dp) {
    Box(Modifier.padding(start = inset).fillMaxWidth().height(0.5.dp).background(Ios.separator))
}

// ── The agent's colour behind a session ───────────────────────────────────────

/**
 * White and grey agents (VS Code, Cursor) would make a dull grey haze: they
 * glow in a deep blue instead.
 */
fun glowHex(hex: String): String {
    val c = colorFromHex(hex)
    val high = max(c.red, max(c.green, c.blue))
    val low = min(c.red, min(c.green, c.blue))
    val saturation = if (high == 0f) 0f else (high - low) / high
    return if (saturation < 0.25f) "#3B5BDB" else hex
}

/**
 * The agent's colour, moving slowly behind a session: a soft glow from the
 * top, fading into black. The iPhone draws it with a 3×3 MeshGradient; Compose
 * has none, so the same nine points become three drifting radial glows over a
 * vertical fade.
 */
@Composable
fun AgentBackdrop(hex: String, modifier: Modifier = Modifier) {
    val base = remember(hex) { colorFromHex(glowHex(hex)) }
    var time by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { time = it }
    }
    Canvas(modifier.fillMaxSize()) {
        val t = (time / 1e9).toFloat()
        val w = size.width
        val h = size.height
        drawRect(Brush.verticalGradient(listOf(base.copy(alpha = 0.30f), base.copy(alpha = 0.10f), Color.Transparent), 0f, h * 0.62f))
        fun glow(x: Float, y: Float, radius: Float, alpha: Float) = drawCircle(
            Brush.radialGradient(listOf(base.copy(alpha = alpha), Color.Transparent), Offset(x * w, y * h), radius * w),
            radius * w, Offset(x * w, y * h),
        )
        glow(0.08f, 0.02f + 0.03f * cos(t * 0.27f), 0.75f, 0.22f)
        glow(0.5f + 0.1f * sin(t * 0.31f), 0f, 0.6f, 0.12f)
        glow(0.92f, 0.04f + 0.03f * sin(t * 0.29f), 0.7f, 0.18f)
        glow(0.5f + 0.12f * cos(t * 0.23f), 0.22f + 0.05f * sin(t * 0.37f), 0.55f, 0.06f)
    }
}

// ── Small shared pieces ───────────────────────────────────────────────────────

/** Apple Pay's "Done": a green ring draws itself, then the checkmark. */
@Composable
fun DrawnCheckmark(size: Dp = 56.dp, modifier: Modifier = Modifier) {
    val ring = remember { Animatable(0f) }
    val tick = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        ring.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(Unit) {
        tick.animateTo(1f, tween(300, delayMillis = 350, easing = FastOutSlowInEasing))
    }
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        drawArc(
            Ios.green, -90f, 360f * ring.value, false,
            style = Stroke(s * 0.07f, cap = StrokeCap.Round),
            topLeft = Offset(s * 0.035f, s * 0.035f),
            size = androidx.compose.ui.geometry.Size(s * 0.93f, s * 0.93f),
        )
        val pad = s * 0.28f
        val box = s - pad * 2
        val check = Path().apply {
            moveTo(pad, pad + box / 2 + box * 0.05f)
            lineTo(pad + box * 0.38f, pad + box - box * 0.12f)
            lineTo(pad + box, pad + box * 0.12f)
        }
        val measure = PathMeasure().apply { setPath(check, false) }
        val shown = Path()
        measure.getSegment(0f, measure.length * tick.value, shown, true)
        drawPath(shown, Ios.green, style = Stroke(s * 0.08f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * A small symbol that moves with the session's state: it breathes while
 * waiting for you and pulses while working. (The iPhone's wiggle on an error
 * and bounce when done are SF Symbols effects; here those two hold still.)
 */
@Composable
fun StateSymbol(session: SessionItem, modifier: Modifier = Modifier, size: Dp = 14.dp) {
    val loop = rememberInfiniteTransition(label = "symbol")
    val breath by loop.animateFloat(0.82f, 1.08f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
    val pulse by loop.animateFloat(0.35f, 1f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "pulse")
    when (session.urgency) {
        0 -> Icon(Sym.handRaisedFill, null, modifier.size(size).scale(breath), tint = Ios.orange)
        1 -> Icon(Sym.questionmarkBubbleFill, null, modifier.size(size).scale(breath), tint = Ios.cyan)
        2 -> Icon(Sym.exclamationmarkTriangleFill, null, modifier.size(size), tint = Ios.red)
        3 -> Icon(Sym.ellipsis, null, modifier.size(size).alpha(pulse), tint = Ios.secondary)
        4 -> Icon(Sym.checkmarkCircleFill, null, modifier.size(size), tint = Ios.green)
        else -> Icon(Sym.moonZzzFill, null, modifier.size(size), tint = Ios.tertiary)
    }
}

/** Deny and Allow: a quiet Deny, a bright Allow with the owner check. */
@Composable
fun ApprovalChoiceButtons(disabled: Boolean = false, deny: () -> Unit, allow: () -> Unit) {
    Row(Modifier.fillMaxWidth().alpha(if (disabled) 0.6f else 1f), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.weight(1f).background(Color.White.copy(alpha = 0.14f), CircleShape)
                .pressable(enabled = !disabled, onClick = deny).padding(vertical = 13.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Deny", style = T.body.semibold)
        }
        Row(
            Modifier.weight(1f).background(Color.White, CircleShape)
                .pressable(enabled = !disabled, onClick = allow).padding(vertical = 13.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Sym.faceid, null, Modifier.size(20.dp), tint = Color.Black)
            Spacer(Modifier.width(6.dp))
            Text("Allow", style = T.body.semibold.color(Color.Black))
        }
    }
}

/** The command waiting for your OK: who asks, the exact command, Deny / Allow. */
@Composable
fun ApprovalPanel(session: SessionItem, disabled: Boolean = false, deny: () -> Unit, allow: () -> Unit) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).shadow(4.dp, CircleShape, ambientColor = Ios.orange, spotColor = Ios.orange).background(Ios.orange, CircleShape))
            Text("${session.pillName} asks to run", style = T.subheadline.semibold.color(Ios.orange))
            Spacer(Modifier.weight(1f))
            Text(session.title, style = T.caption.color(Ios.secondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        SelectionContainer {
            Text(
                session.approvalCommand.ifEmpty { "A permission" },
                style = T.callout.mono.color(Color.White.copy(alpha = 0.92f)),
                maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(),
            )
        }
        ApprovalChoiceButtons(disabled, deny, allow)
    }
}
