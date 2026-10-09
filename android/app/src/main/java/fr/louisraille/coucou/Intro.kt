// The opening — IntroView.swift: Mochi alone on black while the app loads. He
// pops in and looks around like the greeting on the Mac (without the hands):
// quick glances and slow ones, eyes that pop round, shrink or squint to a bar,
// his body leaning, hopping and squashing along. It hides the first look at
// what the phone kept: as soon as it's over he finishes with a little hop and
// flies to his spot on the home screen, the VS Code session's tile (or the
// Mochi of the header), and the home screen behind is already up to date.

package fr.louisraille.coucou

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where the intro's Mochi lands. The tile reports its frame on screen (root coordinates). */
object IntroLanding {
    var tileFrame by mutableStateOf<Rect?>(null)
    var headerFrame by mutableStateOf<Rect?>(null)

    /** The intro is over: the real Mochi on the home screen show again. */
    var landed by mutableStateOf(false)

    val target: Rect? get() = tileFrame ?: headerFrame
}

enum class IntroLandingKind { TILE, HEADER }

/** Reports this view's frame as the intro's landing spot; hidden until he lands. */
@Composable
fun Modifier.introLanding(kind: IntroLandingKind): Modifier {
    if (kind == IntroLandingKind.TILE) {
        DisposableEffect(Unit) {
            onDispose { IntroLanding.tileFrame = null }
        }
    }
    return this
        .onGloballyPositioned {
            // Once he has landed nobody reads the frames: a scrolling list stops reporting its own.
            if (IntroLanding.landed) return@onGloballyPositioned
            val frame = Rect(it.positionInRoot(), it.size.toSize())
            when (kind) {
                IntroLandingKind.TILE -> IntroLanding.tileFrame = frame
                IntroLandingKind.HEADER -> IntroLanding.headerFrame = frame
            }
        }
        .graphicsLayer {
            // Only the spot he actually flies to is hidden.
            val isTarget = kind == IntroLandingKind.TILE || IntroLanding.tileFrame == null
            alpha = if (IntroLanding.landed || !isTarget) 1f else 0f
        }
}

/**
 * He plays at least this long, even when everything is already there, and
 * never keeps the app waiting longer than `MAXIMUM` (milliseconds).
 */
private const val MINIMUM = 2400L
private const val MAXIMUM = 6000L
private val SIZE = 132.dp

/**
 * `ready` is true once the first look at what the phone kept is over. Fills
 * the window, so what it draws is in the same coordinates as the landing frames.
 */
@Composable
fun IntroView(ready: Boolean, onDone: () -> Unit) {
    val engine = remember { IntroEngine() }
    val isReady by rememberUpdatedState(ready)
    val done by rememberUpdatedState(onDone)
    val flight = remember { Animatable(0f) }
    val black = remember { Animatable(1f) }
    var frame by remember { mutableLongStateOf(0L) }
    /** The frame he took off on, 0 while he plays. */
    var flightStart by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) withFrameNanos { frame = it }
    }
    LaunchedEffect(Unit) {
        delay(MINIMUM)
        var waited = MINIMUM
        while (!isReady && waited < MAXIMUM) {
            delay(100)
            waited += 100
        }
        // Everything is there: a last hop looking at you, then off he goes.
        engine.finish()
        delay(480)
        flightStart = frame
        // SwiftUI's .spring(duration: 0.7, bounce: 0.22) and .easeOut(duration: 0.5).delay(0.12).
        launch { flight.animateTo(1f, spring(dampingRatio = 1 - 0.22f, stiffness = (TAU / 0.7f).pow(2))) }
        launch { black.animateTo(0f, tween(500, delayMillis = 120, easing = CubicBezierEasing(0f, 0f, 0.58f, 1f))) }
        delay(700)
        IntroLanding.landed = true
        done()
    }

    Canvas(
        Modifier.fillMaxSize()
            // Until he takes off, nothing behind him can be touched.
            .then(if (flightStart == 0L) Modifier.pointerInput(Unit) {} else Modifier)
            .clearAndSetSemantics {},
    ) {
        val at = frame // read on every draw: each frame draws the next
        val target = IntroLanding.target
        val side = SIZE.toPx()
        val t = flight.value
        drawRect(Color.Black, alpha = black.value.coerceIn(0f, 1f))

        val w = lerp(side, target?.width ?: side, t)
        val h = lerp(side, target?.height ?: side, t)
        val x = lerp(center.x, target?.center?.x ?: center.x, t)
        val y = lerp(center.y, target?.center?.y ?: center.y, t)
        // Over 0.4 s of the flight his face settles back to neutral.
        val settle = if (flightStart == 0L) 0f else min(1f, (at - flightStart) / 0.4e9f)
        // Nowhere to land (another screen is open): he fades where he is.
        val alpha = if (target == null) (1 - t).coerceIn(0f, 1f) else 1f
        drawIntoCanvas {
            val canvas = it.nativeCanvas
            val count = if (alpha < 1f) canvas.saveLayerAlpha(null, (alpha * 255).toInt()) else canvas.save()
            canvas.translate(x - w / 2, y - h / 2)
            engine.draw(canvas, w, h, settle)
            canvas.restoreToCount(count)
        }
    }
}

/**
 * Mochi's face and body for the opening, drawn by the same engine as
 * everywhere else, driven here by a little show of moves. Each move has its
 * own speed, his body follows where he looks (leans, drifts, tilts with the
 * motion) and hops land with a squash, so it never feels mechanical. The
 * eyes stay the same pill and only change size and height: no blink.
 */
private class IntroEngine {
    /** How a move happens: the stiffness and the damping of its spring. */
    class Motion(val k: Float, val c: Float) {
        companion object {
            /** A glance: very fast, a hint of overshoot. */
            val snap = Motion(900f, 42f)

            /** A normal look. */
            val look = Motion(260f, 28f)

            /** A slow, curious drift. */
            val drift = Motion(55f, 14f)

            /** Eyes popping open: bouncy. */
            val pop = Motion(520f, 16f)

            /** Squinting: quick, no bounce. */
            val squint = Motion(700f, 52f)

            /** Reopening after a squint: slow. */
            val ease = Motion(90f, 17f)
        }
    }

    enum class Body { NONE, HOP, SQUASH, SHIVER }

    class Beat(
        val x: Float = 0f, val y: Float = 0f,
        val gaze: Motion = Motion.look,
        val eyes: Float = 1f, val open: Float = 1f,
        val eyeMotion: Motion = Motion.look,
        val body: Body = Body.NONE,
        /** How long he stays on it. */
        val hold: Double,
    )

    companion object {
        /** The show, in order, then random moves from `idle` while the app is still busy. */
        val show = listOf(
            Beat(eyes = 1.05f, eyeMotion = Motion.pop, hold = 0.42), // hello
            Beat(x = -0.75f, y = 0.05f, gaze = Motion.snap, hold = 0.5), // glance left
            Beat(x = 0.62f, y = 0.16f, gaze = Motion.drift, eyes = 0.82f, eyeMotion = Motion.drift, hold = 0.75), // slowly to the right
            Beat(x = 0.08f, y = -0.45f, gaze = Motion.look, eyes = 1.42f, eyeMotion = Motion.pop, body = Body.HOP, hold = 0.55), // up, big round eyes
            Beat(x = 0.28f, y = 0.04f, gaze = Motion.look, eyes = 1.05f, open = 0.2f, eyeMotion = Motion.squint, body = Body.SQUASH, hold = 0.5), // a bar
            Beat(x = -0.42f, y = 0.32f, gaze = Motion.drift, eyes = 0.74f, eyeMotion = Motion.ease, hold = 0.55), // small, down left
            Beat(x = 0.7f, y = -0.04f, gaze = Motion.snap, eyes = 0.9f, hold = 0.16), // double take…
            Beat(x = 0f, y = 0f, gaze = Motion.snap, eyes = 1.25f, eyeMotion = Motion.pop, body = Body.SHIVER, hold = 0.45), // …at you
        )
        val idle = listOf(
            Beat(x = -0.6f, y = -0.2f, gaze = Motion.snap, eyes = 1.15f, eyeMotion = Motion.pop, hold = 0.5),
            Beat(x = 0.55f, y = 0.25f, gaze = Motion.drift, eyes = 0.8f, eyeMotion = Motion.drift, hold = 0.6),
            Beat(x = 0.15f, y = -0.4f, gaze = Motion.look, eyes = 1.35f, eyeMotion = Motion.pop, body = Body.HOP, hold = 0.5),
            Beat(x = -0.2f, y = 0.05f, gaze = Motion.look, open = 0.22f, eyeMotion = Motion.squint, body = Body.SQUASH, hold = 0.45),
            Beat(x = 0.7f, y = 0f, gaze = Motion.snap, hold = 0.35),
            Beat(x = -0.35f, y = 0.3f, gaze = Motion.drift, eyes = 0.72f, eyeMotion = Motion.ease, hold = 0.55),
        )
        val last = Beat(x = 0f, y = 0f, gaze = Motion.look, eyes = 1.12f, eyeMotion = Motion.pop, body = Body.HOP, hold = 10.0)
    }

    private val bot = BotEngine().apply {
        isMini = true
        bodyColor = hexToRGB("#FFFFFF")
    }
    private val ctx = Ctx2D()

    // Damped springs: stiff ones snap (a glance), soft ones drift (a slow look).
    private val yaw = Spring(0f)
    private val pitch = Spring(0f)
    private val eyes = Spring(0.6f)
    private val open = Spring(1f)
    private val sx = Spring(0.25f)
    private val sy = Spring(0.25f)
    private val lean = Spring(0f)
    private val tilt = Spring(0f)

    /** Height off the ground (negative is up) and its speed, with gravity. */
    private var oy = 0f
    private var oyVelocity = 0f

    /** A hop waiting for its crouch to end. */
    private var launchAt: Double? = null
    private var shiverUntil = 0.0

    private var index = 0
    private var nextBeat = 0.0
    private var lastTime: Double? = null
    private var lastIdle = -1
    private var finishing = false

    init {
        // He pops in from nothing.
        sx.go(1f, Motion.pop)
        sy.go(1f, Motion.pop)
    }

    /**
     * Sends a spring to `to`, the way `motion` says. Anim.kt's Spring takes a
     * response and a damping fraction: the same spring as a stiffness k and a
     * damping c, with ω = √k.
     */
    private fun Spring.go(to: Float, motion: Motion) {
        val omega = sqrt(motion.k)
        target = to
        configure(TAU / omega, motion.c / (2 * omega))
    }

    /** The app is ready: back to looking at you, one last hop. */
    fun finish() {
        if (finishing) return
        finishing = true
        play(last, now())
    }

    private fun play(beat: Beat, now: Double) {
        yaw.go(beat.x * 0.62f, beat.gaze)
        pitch.go(beat.y * 0.5f, beat.gaze)
        eyes.go(beat.eyes, beat.eyeMotion)
        open.go(beat.open, if (beat.open < 0.5f) Motion.squint else beat.eyeMotion)
        // The body leans the way he looks, a little behind his eyes.
        lean.go(beat.x * 0.14f, if (beat.gaze.k > 500) Motion.look else Motion.drift)
        tilt.go(beat.x * 0.07f, Motion.drift)
        // A quick glance jolts the body.
        if (beat.gaze.k > 500) {
            sx.velocity += 0.9f
            sy.velocity -= 0.9f
        }
        when (beat.body) {
            Body.HOP -> {
                // Crouch first, then jump.
                sx.velocity += 2.2f
                sy.velocity -= 2.6f
                launchAt = now + 0.1
            }
            Body.SQUASH -> {
                sx.velocity += 1.8f
                sy.velocity -= 2.0f
            }
            Body.SHIVER -> shiverUntil = now + 0.28
            Body.NONE -> {}
        }
        nextBeat = now + beat.hold
    }

    private fun advance(now: Double) {
        if (finishing) return
        if (index < show.size) {
            play(show[index], now)
            index += 1
        } else {
            var pick = Random.nextInt(idle.size)
            if (pick == lastIdle) pick = (pick + 1) % idle.size
            lastIdle = pick
            play(idle[pick], now)
        }
    }

    private fun step(now: Double) {
        val dt = min(1.0 / 30, now - (lastTime ?: now)).toFloat()
        lastTime = now
        if (now >= nextBeat) advance(now)

        val takeoff = launchAt
        if (takeoff != null && now >= takeoff) {
            launchAt = null
            oyVelocity = -2.3f
            sx.velocity -= 2.4f // stretched in the air
            sy.velocity += 3.0f
        }
        // Two half steps, as on the iPhone, so a landing squashes him in the frame it happens.
        repeat(2) {
            val h = dt / 2
            yaw.step(h); pitch.step(h)
            eyes.step(h); open.step(h)
            sx.step(h); sy.step(h)
            lean.step(h); tilt.step(h)
            if (oy < 0 || oyVelocity < 0) {
                oyVelocity += 15 * h
                oy += oyVelocity * h
                if (oy >= 0) {
                    // Landing: squash with the speed he had.
                    sx.velocity += oyVelocity * 0.9f
                    sy.velocity -= oyVelocity * 1.1f
                    oy = 0f
                    oyVelocity = 0f
                }
            }
        }
    }

    /** Draws him in a `w` × `h` box at the canvas's origin; `settle` 0…1 brings him back to neutral. */
    fun draw(canvas: android.graphics.Canvas, w: Float, h: Float, settle: Float) {
        val t = now()
        step(t)
        val keep = 1 - settle
        // A slow breath, on top of the moves.
        val breath = sin(t * 2.4).toFloat() * 0.022f
        val shiver = if (t < shiverUntil) sin(t * 70).toFloat() * 0.035f * ((shiverUntil - t) / 0.28).toFloat() else 0f

        // Leaning into the motion: he tilts with how fast his gaze moves.
        bot.pose(
            BotState.IDLE, showBadge = false,
            yaw = yaw.value * keep, pitch = pitch.value * keep,
            tilt = (tilt.value - yaw.velocity * 0.012f) * keep,
            open = 1 + (open.value - 1) * keep,
            es = 1 + (eyes.value - 1) * keep,
            sx = 1 + (sx.value - 1 - breath * 0.6f) * keep,
            sy = 1 + (sy.value - 1 + breath) * keep,
            ox = (lean.value + shiver) * keep, oy = oy * keep,
        )
        bot.draw(ctx.on(canvas), w, h)
    }
}
