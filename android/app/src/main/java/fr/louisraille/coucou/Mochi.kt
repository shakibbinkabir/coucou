// Mochi — port of windows/src/mochi/engine.ts, itself a direct port of the Mac's
// BotEngine.swift. Same constants, same tweens, same easings, same particles.
// The iPhone app draws every Mochi as a "mini" (flat body in the agent's colour,
// big eyes) and silent, and so does this one. Left out, as nothing here calls
// them: the outfits, the mailbox morph and its mouth, the dance, the sounds.

package fr.louisraille.coucou

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

// ── Types ─────────────────────────────────────────────────────────────────────

enum class EyeShape { PILL, WIDE, DOT, LINE, FLAT, HAPPY, CLOSED, SPIRAL, HEART, STAR, TIRED, WINK }

enum class BadgeKind { DOTS, BANG, QUESTION, DOT }

/** STATES in the prototype: colour, tint, eyes, badge and what loops. */
enum class BotState(
    val color: RGB,
    val tint: Float,
    val eye: EyeShape,
    val badge: BadgeKind? = null,
    val bounces: Boolean = false,
    val scans: Boolean = false,
    val breathes: Boolean = false,
    val zz: Boolean = false,
    val sweat: Boolean = false,
    val look: FloatArray? = null,
    val tilt: Float = 0f,
    val sound: String? = null,
) {
    IDLE(rgbOf(0.902f, 0.914f, 0.933f), 0f, EyeShape.PILL),
    WORKING(rgbOf(0.231f, 0.62f, 1f), 0.72f, EyeShape.PILL, BadgeKind.DOTS, sound = "work"),
    THINKING(rgbOf(0.545f, 0.361f, 0.965f), 0.72f, EyeShape.PILL, BadgeKind.DOTS, look = floatArrayOf(0.55f, 0.55f), sound = "think"),
    SEARCHING(rgbOf(0.388f, 0.396f, 0.949f), 0.72f, EyeShape.PILL, BadgeKind.DOTS, scans = true, sound = "search"),
    APPROVAL(rgbOf(0.961f, 0.647f, 0.141f), 0.78f, EyeShape.WIDE, BadgeKind.BANG, bounces = true, sound = "approval"),
    QUESTION(rgbOf(0.133f, 0.827f, 0.933f), 0.75f, EyeShape.PILL, BadgeKind.QUESTION, tilt = 0.17f, sound = "question"),
    ERROR(rgbOf(0.957f, 0.314f, 0.369f), 0.78f, EyeShape.FLAT, BadgeKind.DOT, sound = "error"),
    FINISHED(rgbOf(0.204f, 0.831f, 0.6f), 0.35f, EyeShape.HAPPY, BadgeKind.DOT, sound = "finish"),
    RATELIMIT(rgbOf(0.984f, 0.573f, 0.235f), 0.72f, EyeShape.TIRED, BadgeKind.DOT, sweat = true, sound = "rate"),
    SLEEPING(rgbOf(0.58f, 0.635f, 0.722f), 0.32f, EyeShape.CLOSED, breathes = true, zz = true, sound = "sleep"),
    DIZZY(rgbOf(0.957f, 0.447f, 0.714f), 0.7f, EyeShape.SPIRAL, sound = "dizzy"),
}

enum class BotEmote(val eye: EyeShape) {
    LOVE(EyeShape.HEART), SURPRISED(EyeShape.DOT), PROUD(EyeShape.STAR), WINK(EyeShape.WINK),
    YAWN(EyeShape.TIRED), HAPPY(EyeShape.HAPPY), ANNOYED(EyeShape.LINE),
}

class Key(val target: Float, val ms: Float, val ease: EaseFn)

private fun k(target: Float, ms: Number, ease: EaseFn) = Key(target, ms.toFloat(), ease)

private class Tween(
    val prop: Int, val keys: Array<out Key>, var index: Int, var from: Float, var startMs: Long,
    val onComplete: (() -> Unit)?,
)

private enum class Bit { HEART, STAR, SPARK, SWEAT, Z }

private class Particle(
    val type: Bit, val x: Float, val y: Float, val vx: Float, val vy: Float,
    var age: Float, val life: Float, val rot: Float, val size: Float,
)

// ── Constants (MochiConst / PISTES.mochi) ─────────────────────────────────────

private const val EYE_W = 0.25f
private const val EYE_H = 0.27f
private const val EYE_SP = 0.37f
private const val EYE_P = -0.12f
private val BASE_TOP = rgbOf(0.929f, 0.929f, 0.937f) // #EDEDEF
private val BASE_BOTTOM = rgbOf(0.769f, 0.773f, 0.792f) // #C4C5CA
private val INK = rgb(26, 20, 18) // #1A1412
private val MINI_INK = rgb(16, 19, 26) // #10131A
private val HEART = hex("#FF4D6D")
private val STAR = hex("#F7B32B")
private val SWEAT = hex("#7CC7FF")
private val ZED = rgb(209, 219, 235)
private val WHITE3 = rgbOf(1f, 1f, 1f)

// Animated properties (BotEngine `s`), by index.
private const val YAW = 0
private const val PITCH = 1
private const val ROLL = 2
private const val TILT = 3
private const val OPEN = 4
private const val SX = 5
private const val SY = 6
private const val OY = 7
private const val OX = 8
private const val TINT = 9
private const val HANDS = 10
private const val BLUSH = 11
private const val ES = 12
private const val BADGE_S = 13

private fun rnd() = Random.nextFloat()

private fun heartPath(x: Ctx2D, s: Float) {
    x.beginPath()
    x.moveTo(0f, s * 0.38f)
    x.bezierCurveTo(-s * 1.05f, -s * 0.15f, -s * 0.5f, -s * 0.95f, 0f, -s * 0.38f)
    x.bezierCurveTo(s * 0.5f, -s * 0.95f, s * 1.05f, -s * 0.15f, 0f, s * 0.38f)
    x.closePath()
}

private fun starPath(x: Ctx2D, ro: Float, ri: Float) {
    x.beginPath()
    for (i in 0 until 10) {
        val r = if (i % 2 == 1) ri else ro
        val a = -PI_F / 2 + i * PI_F / 5
        x.lineTo(cos(a) * r, sin(a) * r)
    }
    x.closePath()
}

// ── Engine ────────────────────────────────────────────────────────────────────

class BotEngine {
    var isMini = false

    /** Solid body colour for mini bots / integration pills (null = Mochi gradient). */
    var bodyColor: RGB? = null

    private val p = FloatArray(14).also {
        it[OPEN] = 1f
        it[SX] = 1f
        it[SY] = 1f
        it[ES] = 1f
    }
    val yaw get() = p[YAW]
    val pitch get() = p[PITCH]
    val roll get() = p[ROLL]
    val tilt get() = p[TILT]
    val open get() = p[OPEN]
    val sx get() = p[SX]
    val sy get() = p[SY]
    val oy get() = p[OY]
    val ox get() = p[OX]
    val hands get() = p[HANDS]
    val blush get() = p[BLUSH]
    val es get() = p[ES]
    val badgeS get() = p[BADGE_S]

    // Targets
    private var tgYaw = 0f
    private var tgPitch = 0f
    private var tgTilt = 0f
    private var tgSy = 1f
    private var tgSx = 1f
    var tgEs = 1f

    /** Extra canvas height above the body so hearts can fly out without clipping. */
    var particleOverhang = 0f

    private var col: RGB = BotState.IDLE.color
    private var colT: RGB = BotState.IDLE.color

    var state = BotState.IDLE
        private set

    private var eyeOverride: EyeShape? = null
    private var eyeOverrideUntil = 0.0
    private var permanentEye: EyeShape? = null
    private var permanentEmote: BotEmote? = null
    private var miniNextBehavior = 0.0

    private var badge: BadgeKind? = null
    private var badgeColor: RGB = col
    private var badgeKey = "none"
    private var badgeToken = 0

    private val tweens = HashMap<Int, Tween>()
    private val particles = ArrayList<Particle>()

    var lookX = 0f
    var lookY = 0f

    private val t0 = now() - rnd() * 5
    private var nextBlink = now() + 1.5 + rnd() * 2
    private var waveUntil = 0.0
    private var waveStart = 0.0
    private var greetToken = 0
    private var lastAmbient = 0.0
    private val slapTimes = ArrayList<Double>()
    private var miniLookX = 0f
    private var miniLookY = 0f
    private var miniLookNextTime = 0.0

    /** Fired when three slaps land inside 1.7 s (→ dizzy + confused view). */
    var onDizzy: (() -> Unit)? = null

    /** Something started moving outside a frame (a timer): the owner draws again. */
    var onWake: (() -> Unit)? = null

    private fun locked(prop: Int) = tweens.containsKey(prop)

    // ── Public API ──────────────────────────────────────────────────────────────

    fun setState(next: BotState, force: Boolean = false) {
        if (state == next && !force) return
        val prev = state
        state = next
        colT = next.color
        if (!locked(TINT)) p[TINT] = next.tint
        if (!locked(TILT)) tgTilt = next.tilt
        setBadge(next.badge, next.color)

        when (next) {
            BotState.FINISHED -> {
                doRoll(950f, 1f)
                after(500) { emit(Bit.SPARK, 5) }
            }
            BotState.ERROR -> anim(
                OX, k(0.08f, 50, Ease.out), k(-0.08f, 70, Ease.inOut),
                k(0.05f, 70, Ease.inOut), k(0f, 90, Ease.out),
            )
            BotState.APPROVAL -> anim(OY, k(-0.2f, 150, Ease.out), k(0f, 300, Ease.back))
            BotState.DIZZY -> doRoll(1300f, 2f)
            BotState.QUESTION -> blink()
            BotState.RATELIMIT -> emit(Bit.SWEAT, 1)
            else -> if (prev != BotState.IDLE || next != BotState.IDLE) blink()
        }
        onWake?.invoke()
    }

    private fun setBadge(b: BadgeKind?, color: RGB) {
        val key = if (b != null) "$b-${color.joinToString(",")}" else "none"
        if (key == badgeKey) return
        badgeKey = key
        val tok = ++badgeToken
        anim(BADGE_S, k(0f, 90, Ease.inOut))
        after(100) {
            if (tok != badgeToken) return@after
            badge = b
            badgeColor = color
            if (b != null) anim(BADGE_S, k(1f, 280, Ease.back))
        }
    }

    fun blink() {
        if (locked(OPEN)) return
        anim(OPEN, k(0.06f, 70, Ease.inOut), k(1f, 130, Ease.out))
    }

    fun squash() {
        anim(SY, k(0.78f, 70, Ease.out), k(1.1f, 130, Ease.out), k(1f, 170, Ease.inOut))
        anim(SX, k(1.16f, 70, Ease.out), k(0.95f, 130, Ease.out), k(1f, 170, Ease.inOut))
    }

    fun slap() {
        interruptGreet()
        if (state == BotState.DIZZY) return
        val t = now()
        slapTimes.removeAll { t - it >= 1.7 }
        slapTimes.add(t)
        squash()
        if (slapTimes.size >= 3) {
            slapTimes.clear()
            onDizzy?.invoke()
        } else {
            eyeOverride = EyeShape.LINE
            eyeOverrideUntil = t + 0.8
        }
    }

    private fun doRoll(durationMs: Float, turns: Float) {
        p[ROLL] = 0f
        anim(ROLL, k(TAU * turns, durationMs, Ease.inOut)) { p[ROLL] = 0f }
    }

    /** Peek wave — the "coucou". Timings from BotEngine.greet(). */
    fun greet() {
        val t = now()
        val tok = ++greetToken
        waveStart = t + 0.45
        waveUntil = t + 1.55

        eyeOverride = EyeShape.HAPPY
        eyeOverrideUntil = t + 2.0
        anim(OY, k(-0.06f, 220, Ease.out), k(0f, 220, Ease.back))

        after(250) {
            if (greetToken != tok) return@after
            anim(HANDS, k(1f, 280, Ease.out))
            anim(SY, k(0.95f, 100, Ease.out), k(1f, 260, Ease.back))
            anim(SX, k(1.04f, 100, Ease.out), k(1f, 260, Ease.back))
        }
        after(550) { if (greetToken == tok) blink() }
        after(1500) { if (greetToken == tok) blink() }
        after(1550) {
            if (greetToken != tok) return@after
            waveUntil = 0.0
            anim(HANDS, k(0f, 200, Ease.inOut))
        }
        after(1750) {
            if (greetToken != tok) return@after
            eyeOverride = EyeShape.HAPPY
            eyeOverrideUntil = now() + 0.3
        }
    }

    private fun interruptGreet() {
        if (hands <= 0.01f && now() >= waveUntil) return
        greetToken++
        waveUntil = 0.0
        waveStart = 0.0
        anim(HANDS, k(0f, 150, Ease.inOut))
    }

    fun setPermanentEmote(emote: BotEmote?) {
        permanentEmote = emote
        if (emote == BotEmote.WINK) {
            miniNextBehavior = now() + 0.8 + rnd() * 1.7
            return
        }
        permanentEye = emote?.eye
        if (permanentEye != null) {
            eyeOverride = permanentEye
            eyeOverrideUntil = Double.POSITIVE_INFINITY
        } else if (eyeOverrideUntil == Double.POSITIVE_INFINITY) {
            eyeOverride = null
            eyeOverrideUntil = 0.0
        }
        miniNextBehavior = now() + 0.8 + rnd() * 1.7
    }

    fun triggerEmote(emote: BotEmote, duration: Float = 1.8f) {
        val t = now()
        eyeOverride = emote.eye
        eyeOverrideUntil = t + duration

        when (emote) {
            BotEmote.LOVE -> {
                anim(BLUSH, k(1f, 300, Ease.out), k(1f, (duration - 0.6f) * 1000, Ease.lin), k(0f, 300, Ease.inOut))
                emit(Bit.HEART, 4)
                anim(OY, k(-0.1f, 160, Ease.out), k(0f, 300, Ease.back))
            }
            BotEmote.SURPRISED -> {
                anim(OY, k(-0.3f, 140, Ease.out), k(0f, 380, Ease.back))
                anim(ES, k(1.25f, 120, Ease.out), k(1f, 500, Ease.inOut))
            }
            BotEmote.PROUD -> {
                emit(Bit.STAR, 5)
                anim(TILT, k(-0.14f, 220, Ease.out), k(-0.14f, (duration - 0.5f) * 1000, Ease.lin), k(0f, 280, Ease.inOut))
                anim(BLUSH, k(0.7f, 250, Ease.out), k(0.7f, (duration - 0.5f) * 1000, Ease.lin), k(0f, 300, Ease.inOut))
            }
            BotEmote.WINK ->
                anim(TILT, k(0.12f, 160, Ease.out), k(0.12f, (duration - 0.4f) * 1000, Ease.lin), k(0f, 240, Ease.inOut))
            BotEmote.YAWN -> {
                anim(SY, k(1.12f, 500, Ease.inOut), k(1f, 500, Ease.inOut))
                anim(SX, k(0.94f, 500, Ease.inOut), k(1f, 500, Ease.inOut))
                after(700) {
                    eyeOverride = EyeShape.CLOSED
                    emit(Bit.Z, 2)
                }
            }
            BotEmote.HAPPY -> anim(BLUSH, k(0.6f, 200, Ease.out), k(0f, 600, Ease.inOut))
            BotEmote.ANNOYED -> {
                eyeOverride = EyeShape.LINE
                eyeOverrideUntil = t + 0.8
            }
        }
        onWake?.invoke()
    }

    private fun emit(type: Bit, count: Int) {
        for (i in 0 until count) {
            val isZ = type == Bit.Z
            particles.add(
                Particle(
                    type,
                    x = (rnd() - 0.5f) * 0.9f + (if (isZ) 0.55f else 0f),
                    y = -0.7f - rnd() * 0.2f,
                    vx = (rnd() - 0.5f) * 0.35f + (if (isZ) 0.18f else 0f),
                    vy = -(0.45f + rnd() * 0.35f),
                    age = -i * 0.14f,
                    life = 1.3f + rnd() * 0.5f,
                    rot = rnd() * TAU,
                    size = 0.15f + rnd() * 0.08f,
                ),
            )
        }
        onWake?.invoke()
    }

    /**
     * A fixed pose, for a Mochi that does not move (MochiStill on the iPhone:
     * lists, widgets, notifications): the state's colour and eyes, where he
     * looks, and his badge fully out. Nothing is animated. The opening (Intro.kt)
     * moves him with springs of its own and sets every frame this way: the size
     * of his eyes, his squash and where he stands too.
     */
    fun pose(
        state: BotState, yaw: Float = 0f, pitch: Float = 0f, tilt: Float = 0f, open: Float = 1f,
        eye: EyeShape? = null, showBadge: Boolean = true,
        es: Float = 1f, sx: Float = 1f, sy: Float = 1f, ox: Float = 0f, oy: Float = 0f,
    ) {
        this.state = state
        col = state.color
        colT = state.color
        p[YAW] = yaw
        p[PITCH] = pitch
        p[TILT] = tilt
        p[OPEN] = open
        p[ES] = es
        p[SX] = sx
        p[SY] = sy
        p[OX] = ox
        p[OY] = oy
        if (eye != null) {
            permanentEye = eye
            eyeOverride = eye
            eyeOverrideUntil = Double.POSITIVE_INFINITY
        }
        badge = if (showBadge) state.badge else null
        badgeColor = state.color
        p[BADGE_S] = 1f
    }

    /** True while anything is still moving — lets the island stop its frame loop. */
    val busy: Boolean
        get() = tweens.isNotEmpty() ||
            particles.isNotEmpty() ||
            state.bounces || state.scans || state.breathes || state.zz || state.sweat ||
            state.badge == BadgeKind.DOTS || state == BotState.DIZZY ||
            isMini ||
            abs(tgYaw - yaw) > 0.002f ||
            abs(tgPitch - pitch) > 0.002f ||
            abs(tgTilt - tilt) > 0.002f ||
            abs(tgSy - sy) > 0.002f ||
            abs(tgSx - sx) > 0.002f ||
            abs(tgEs - es) > 0.002f ||
            abs(col[0] - colT[0]) > 0.003f ||
            abs(col[1] - colT[1]) > 0.003f ||
            abs(col[2] - colT[2]) > 0.003f ||
            now() < waveUntil ||
            (eyeOverride != null && eyeOverrideUntil != Double.POSITIVE_INFINITY)

    // ── Tweens ──────────────────────────────────────────────────────────────────

    private fun anim(prop: Int, vararg keys: Key, onComplete: (() -> Unit)? = null) {
        tweens[prop] = Tween(prop, keys, 0, p[prop], nowMs(), onComplete)
        onWake?.invoke()
    }

    // ── Update ──────────────────────────────────────────────────────────────────

    fun update(dt: Float) {
        val n = now()
        val nowMs = nowMs()

        for (tw in tweens.values.toList()) {
            val key = tw.keys[tw.index]
            val f = min(1f, max(0f, (nowMs - tw.startMs) / key.ms))
            p[tw.prop] = tw.from + (key.target - tw.from) * key.ease(f)
            if (f >= 1f) {
                tw.from = key.target
                tw.index += 1
                tw.startMs = nowMs
                if (tw.index >= tw.keys.size) {
                    // Only if it is still this tween: onComplete of another may have replaced it.
                    if (tweens[tw.prop] === tw) tweens.remove(tw.prop)
                    tw.onComplete?.invoke()
                }
            }
        }

        val t = (n - t0).toFloat()
        var ty = lookX * 0.62f
        var tp = lookY * 0.5f

        state.look?.let {
            ty = ty * 0.35f + it[0] * 0.55f
            tp = tp * 0.3f + it[1] * 0.5f
        }
        if (state.scans) {
            ty = sin(t * 2.6f) * 0.6f
            tp = -0.06f
        }
        if (state == BotState.SLEEPING) {
            ty = 0f
            tp = -0.14f
        }
        if (state == BotState.DIZZY) ty = sin(t * 9) * 0.25f

        // Mini bots never follow the pointer — they wander.
        if (isMini && state.look == null && !state.scans && state != BotState.SLEEPING && state != BotState.DIZZY) {
            if (n > miniLookNextTime) {
                miniLookX = -0.88f + rnd() * 1.76f
                miniLookY = -0.55f + rnd() * 1.0f
                miniLookNextTime = n + 0.5 + rnd() * 1.5
            }
            ty = miniLookX * 0.62f
            tp = miniLookY * 0.5f
        }

        tgYaw = ty
        tgPitch = tp
        tgTilt = state.tilt

        if (n > waveStart && n < waveUntil) {
            val wt = (n - waveStart).toFloat()
            tgTilt = -0.06f + sin(TAU * 1.2f * wt) * 0.07f
        }

        val bounce = if (state.bounces) -abs(sin(t * 5.2f)) * 0.07f else 0f
        val kGen = 1 - 0.0008f.pow(dt)
        if (!locked(OY)) p[OY] += (bounce - p[OY]) * kGen

        if (state.breathes) {
            val amp = if (isMini) 0.07f else 0.035f
            tgSy = 1 + sin(t * 1.8f) * amp
            tgSx = 1 - sin(t * 1.8f) * amp * 0.57f
        } else if (isMini) {
            tgSy = 1 + sin(t * 2.2f) * 0.04f
            tgSx = 1 - sin(t * 2.2f) * 0.02f
        } else {
            tgSy = 1f
            tgSx = 1f
        }

        if (isMini && n > miniNextBehavior) doMiniBehaviorLoop()

        val kLook = 1 - 0.0025f.pow(dt)
        if (!locked(YAW)) p[YAW] += (tgYaw - p[YAW]) * kLook
        if (!locked(PITCH)) p[PITCH] += (tgPitch - p[PITCH]) * kLook
        if (!locked(TILT)) p[TILT] += (tgTilt - p[TILT]) * kGen
        if (!locked(SY)) p[SY] += (tgSy - p[SY]) * kGen
        if (!locked(SX)) p[SX] += (tgSx - p[SX]) * kGen
        if (!locked(ES)) p[ES] += (tgEs - p[ES]) * kGen

        col = mix3(col, colT, 1 - 0.002f.pow(dt))

        if (n > nextBlink) {
            if (state != BotState.SLEEPING && state != BotState.DIZZY) {
                blink()
                if (rnd() < 0.22f) after(230) { blink() }
            }
            nextBlink = n + 2.2 + rnd() * 3.2
        }

        if (eyeOverride != null && n > eyeOverrideUntil) {
            eyeOverride = permanentEye
            if (permanentEye != null) eyeOverrideUntil = Double.POSITIVE_INFINITY
        }

        if (n - lastAmbient > 1.3) {
            lastAmbient = n
            if (state.zz) emit(Bit.Z, 1)
            if (!isMini && state.sweat && rnd() < 0.5f) emit(Bit.SWEAT, 1)
        }

        for (q in particles) q.age += dt
        particles.removeAll { it.age >= it.life }

    }

    /** The next blink is due: a resting Mochi wakes his owner for it. */
    val nextBlinkInMs: Long get() = max(0.0, (nextBlink - now()) * 1000).toLong()

    private fun doMiniBehaviorLoop() {
        val n = now()
        when (permanentEmote) {
            BotEmote.HAPPY -> {
                if (locked(OY)) {
                    miniNextBehavior = n + 0.4
                    return
                }
                anim(OY, k(-0.3f, 120, Ease.out), k(0.03f, 200, Ease.inOut), k(0f, 160, Ease.back))
                anim(SY, k(0.82f, 80, Ease.out), k(1.18f, 130, Ease.out), k(0.88f, 160, Ease.inOut), k(1f, 200, Ease.back))
                anim(SX, k(1.15f, 80, Ease.out), k(0.88f, 130, Ease.out), k(1.06f, 160, Ease.inOut), k(1f, 200, Ease.back))
                miniNextBehavior = n + 2.2 + rnd() * 1.2
            }
            BotEmote.ANNOYED -> {
                if (locked(YAW)) {
                    miniNextBehavior = n + 0.5
                    return
                }
                anim(
                    YAW, k(-0.65f, 50, Ease.out), k(0.65f, 90, Ease.inOut), k(-0.5f, 80, Ease.inOut),
                    k(0.4f, 75, Ease.inOut), k(-0.2f, 70, Ease.inOut), k(0f, 140, Ease.out),
                )
                miniNextBehavior = n + 3.0 + rnd() * 2.5
            }
            BotEmote.WINK -> {
                eyeOverride = EyeShape.WINK
                eyeOverrideUntil = n + 0.55
                anim(TILT, k(0.13f, 100, Ease.out), k(0.13f, 320, Ease.lin), k(0f, 200, Ease.inOut))
                miniNextBehavior = n + 2.2 + rnd() * 2.0
            }
            BotEmote.LOVE -> {
                emit(Bit.HEART, 2)
                anim(TILT, k(-0.1f, 180, Ease.out), k(0.1f, 340, Ease.inOut), k(0f, 220, Ease.inOut))
                miniNextBehavior = n + 2.6 + rnd() * 1.5
            }
            else -> miniNextBehavior = n + 3.0 + rnd() * 2.0
        }
    }

    // ── Draw ────────────────────────────────────────────────────────────────────

    /**
     * Draws hands, body, blush, eyes, badge and particles into a square of `W`
     * (the body is 60 % of it), `H` tall when there is room above for particles.
     */
    fun draw(x: Ctx2D, W: Float, H: Float) {
        val R = W * 0.3f
        val rx = R * 1.14f
        val ry = R * 0.88f
        val cx = W / 2 + ox * R
        val cy = H / 2 + particleOverhang / 2 + oy * R + R * 0.06f

        drawHandsBehind(x, R, rx, ry, cx, cy)

        x.save()
        x.translate(cx, cy)
        if (tilt != 0f) x.rotate(tilt)
        x.scale(sx, sy)

        val body = bodyPath(rx, ry)
        drawBody(x, body, R, rx, ry)

        val blushVal = max(blush, p[TINT] * 0.5f)
        if (blushVal > 0.01f) {
            x.save()
            x.clip(body)
            val yOffset = sin(yaw) * rx * 0.8f
            x.fillStyle = rgba(255, 120, 150, 0.5f * blushVal)
            for (sd in intArrayOf(-1, 1)) {
                x.beginPath()
                x.ellipse(sd * rx * 0.55f + yOffset, ry * 0.2f, R * 0.17f, R * 0.1f, 0f, 0f, TAU)
                x.fill()
            }
            x.restore()
        }

        drawEyes(x, body, R, rx, ry)

        x.restore()

        val b = badge
        if (b != null && badgeS > 0.01f) drawBadge(x, b, R, cx, cy)
        drawParticles(x, R, cx, cy)
    }

    /** The squircle: a superellipse of exponent 2.7. */
    private fun bodyPath(rx: Float, ry: Float): Path {
        val n = 72
        val expN = 2.0f / 2.7f
        val path = Path()
        for (i in 0..n) {
            val a = i.toFloat() / n * TAU
            val ca = cos(a)
            val sa = sin(a)
            val px = rx * (if (ca >= 0) ca.pow(expN) else -(-ca).pow(expN))
            val py = ry * (if (sa >= 0) sa.pow(expN) else -(-sa).pow(expN))
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        return path
    }

    private fun drawBody(x: Ctx2D, body: Path, R: Float, rx: Float, ry: Float) {
        val flat = bodyColor
        if (flat != null) {
            // Mini bots: flat solid fill — no gradient, no reflection, no highlight
            x.fillStyle = rgba(flat, 1f)
            x.fill(body)
            return
        } else {
            val g = x.createLinearGradient(rx * 0.7f, -ry * 0.85f, -rx * 0.8f, ry * 0.9f)
            g.addColorStop(0f, rgba(BASE_TOP))
            g.addColorStop(1f, rgba(BASE_BOTTOM))
            x.fillStyle = g
            x.fill(body)
        }
        val effectiveTint = p[TINT]
        if (effectiveTint > 0.01f) {
            val tg = x.createLinearGradient(0f, ry, 0f, -ry)
            tg.addColorStop(0f, rgba(col, 0.72f * effectiveTint))
            tg.addColorStop(1f, rgba(col, 0f))
            x.fillStyle = tg
            x.fill(body)
        }

        val sh = x.createRadialGradient(0f, 0f, R * 0.15f, 0f, 0f, R * 1.25f)
        sh.addColorStop(0f, Color.TRANSPARENT)
        sh.addColorStop(0.6f, Color.TRANSPARENT)
        sh.addColorStop(1f, rgba(0, 0, 0, 0.2f))
        x.fillStyle = sh
        x.fill(body)

        val hl = x.createRadialGradient(rx * 0.34f, -ry * 0.46f, 0f, rx * 0.34f, -ry * 0.46f, R * 0.42f)
        hl.addColorStop(0f, rgba(255, 255, 255, 0.55f))
        hl.addColorStop(1f, rgba(255, 255, 255, 0f))
        x.fillStyle = hl
        x.fill(body)
    }

    private fun drawEyes(x: Ctx2D, body: Path, R: Float, rx: Float, ry: Float) {
        val shape = eyeOverride ?: state.eye

        x.save()
        x.clip(body)
        val ink = if (isMini) MINI_INK else INK
        x.fillStyle = ink
        x.strokeStyle = ink

        for (sd in intArrayOf(-1, 1)) {
            val eyeYaw = sd * EYE_SP + yaw
            var eyePitch = EYE_P + pitch + roll
            eyePitch = (((eyePitch + PI_F) % TAU) + TAU) % TAU - PI_F
            val cp = cos(eyePitch)
            if (cos(eyeYaw) * cp <= 0.04f) continue

            val ex = sin(eyeYaw) * cp * rx
            val ey = -sin(eyePitch) * ry
            val fx = max(0.18f, cos(eyeYaw))
            val fy = max(0.18f, cp)
            val eyeMult = if (isMini) 1.9f else 1.0f
            val ew = R * EYE_W * es * eyeMult
            val eh = R * EYE_H * es * eyeMult

            x.save()
            x.translate(ex, ey)
            x.scale(fx, fy)
            drawEyeShape(x, shape, ew, eh, sd, ink)
            x.restore()
        }
        x.restore()
    }

    private fun drawEyeShape(x: Ctx2D, shape: EyeShape, w: Float, h: Float, sd: Int, ink: Int) {
        val t = (now() - t0).toFloat()
        when (shape) {
            EyeShape.WIDE -> drawEyeShape(x, EyeShape.PILL, w * 1.16f, h * 1.12f, sd, ink)
            EyeShape.PILL -> {
                val hh = max(h * open, w * 0.3f)
                x.roundRectPath(-w / 2, -hh / 2, w, hh, min(w / 2, hh / 2))
                x.fill()
            }
            EyeShape.DOT -> {
                x.beginPath()
                x.arc(0f, 0f, w * 0.45f, 0f, TAU)
                x.fill()
            }
            EyeShape.LINE -> {
                x.rotate(-sd * 0.2f)
                x.roundRectPath(-w * 0.78f, -w * 0.21f, w * 1.56f, w * 0.42f, w * 0.21f)
                x.fill()
            }
            EyeShape.FLAT -> {
                x.roundRectPath(-w * 0.72f, -w * 0.2f, w * 1.44f, w * 0.4f, w * 0.2f)
                x.fill()
            }
            EyeShape.HAPPY -> {
                x.lineWidth = w * 0.5f
                x.lineCap = Paint.Cap.ROUND
                x.beginPath()
                x.arc(0f, h * 0.18f, w * 0.82f, PI_F * 1.12f, PI_F * 1.88f)
                x.stroke()
            }
            EyeShape.CLOSED -> {
                x.lineWidth = w * 0.36f
                x.lineCap = Paint.Cap.ROUND
                x.beginPath()
                x.arc(0f, -h * 0.08f, w * 0.78f, PI_F * 0.15f, PI_F * 0.85f)
                x.stroke()
            }
            EyeShape.SPIRAL -> {
                x.lineWidth = w * 0.22f
                x.lineCap = Paint.Cap.ROUND
                x.beginPath()
                var a = 0f
                while (a < 4.4f * PI_F) {
                    val r = w * 0.06f + a * w * 0.058f
                    val aa = a + t * 9 * sd
                    x.lineTo(cos(aa) * r, sin(aa) * r)
                    a += 0.2f
                }
                x.stroke()
            }
            EyeShape.HEART -> {
                x.fillStyle = HEART
                heartPath(x, w * 1.2f)
                x.fill()
                x.fillStyle = ink
            }
            EyeShape.STAR -> {
                x.fillStyle = STAR
                x.rotate(t * 1.5f * sd)
                starPath(x, w * 1.05f, w * 0.46f)
                x.fill()
                x.fillStyle = ink
            }
            EyeShape.TIRED -> {
                x.roundRectPath(-w / 2, -h * 0.02f, w, h * 0.38f, w / 2)
                x.fill()
                x.roundRectPath(-w * 0.62f, -h * 0.1f, w * 1.24f, w * 0.22f, w * 0.11f)
                x.fill()
            }
            EyeShape.WINK -> if (sd < 0) {
                val hh = max(h * open, w * 0.3f)
                x.roundRectPath(-w / 2, -hh / 2, w, hh, min(w / 2, hh / 2))
                x.fill()
            } else {
                x.lineWidth = w * 0.5f
                x.lineCap = Paint.Cap.ROUND
                x.beginPath()
                x.arc(0f, h * 0.18f, w * 0.82f, PI_F * 1.12f, PI_F * 1.88f)
                x.stroke()
            }
        }
    }

    /** Hands sit behind the body — drawn before it, in world coordinates. */
    private fun drawHandsBehind(x: Ctx2D, R: Float, rx: Float, ry: Float, cx: Float, cy: Float) {
        if (hands <= 0.01f || isMini) return
        if (R <= 14) return // meaningless at compact/peek sizes

        val n = now()
        val bodyH = 2 * ry
        val hew = 0.3f * ry * hands
        val heh = 0.26f * ry * hands
        val hwB = rx * sx
        val hhB = ry * sy
        val isWaving = n >= waveStart && waveStart > 0 && n < waveUntil
        val wt = (n - waveStart).toFloat()

        for (sd in intArrayOf(-1, 1)) {
            val localX: Float
            val localY: Float
            var handRot = 0f

            if (sd > 0 && isWaving) {
                val rise = min(1f, wt / 0.18f)
                val riseEased = 1 - (1 - rise).pow(3)
                val restX = hwB * 1.08f
                val restY = hhB * 0.7f
                val oscX = cos(13 * wt) * 0.06f * bodyH
                val oscY = -sin(13 * wt) * 0.14f * bodyH
                val waveX = hwB * 1.1f + oscX
                val waveY = -hhB * 0.15f + oscY
                localX = restX + (waveX - restX) * riseEased
                localY = restY + (waveY - restY) * riseEased
                handRot = (-0.5f + sin(13 * wt) * 0.35f) * riseEased
            } else if (sd < 0 && isWaving) {
                localX = -hwB * 1.08f
                localY = hhB * 0.7f + sin(6 * wt) * 0.04f * bodyH
            } else {
                localX = sd * hwB * 1.08f
                localY = hhB * 0.7f
            }

            val cosT = cos(tilt)
            val sinT = sin(tilt)
            val worldX = cx + cosT * localX - sinT * localY
            val worldY = cy + sinT * localX + cosT * localY

            x.save()
            x.translate(worldX, worldY)
            if (handRot != 0f) x.rotate(handRot)
            val g = x.createLinearGradient(hew * 0.7f, -heh * 0.85f, -hew * 0.8f, heh * 0.9f)
            val flat = bodyColor
            if (flat != null) {
                g.addColorStop(0f, rgba(mix3(flat, WHITE3, 0.35f)))
                g.addColorStop(1f, rgba(flat))
            } else {
                g.addColorStop(0f, rgba(BASE_TOP))
                g.addColorStop(1f, rgba(BASE_BOTTOM))
            }
            x.beginPath()
            x.ellipse(0f, 0f, hew, heh, 0f, 0f, TAU)
            x.fillStyle = g
            x.fill()
            x.strokeStyle = rgba(0, 0, 0, 0.08f)
            x.lineWidth = 1f
            x.stroke()
            x.restore()
        }
    }

    private fun drawBadge(x: Ctx2D, kind: BadgeKind, R: Float, cx: Float, cy: Float) {
        val bs = badgeS * (if (isMini) 1.25f else 1f)
        val bx = cx - R * 0.72f * sx
        val by = cy - R * 0.72f * sy
        val t = (now() - t0).toFloat()

        x.save()
        x.translate(bx, by)
        x.scale(bs, bs)
        val col = rgba(badgeColor)

        fun disc(r: Float, color: Int) {
            x.fillStyle = color
            x.beginPath()
            x.arc(0f, 0f, r, 0f, TAU)
            x.fill()
        }

        when (kind) {
            BadgeKind.DOTS -> if (isMini) {
                val phase = (t * 2.4f) % 1f
                disc(R * 0.2f, Color.BLACK)
                disc(R * 0.22f * (1 + 0.25f * sin(phase * TAU)), col)
            } else {
                val pw = R * 0.72f
                val ph = R * 0.36f
                x.roundRectPath(-pw / 2, -ph / 2, pw, ph, ph / 2)
                x.fillStyle = col
                x.fill()
                for (i in 0 until 3) {
                    val phase = (((t * 2.4f - i * 0.22f) % 1f) + 1f) % 1f
                    val dotR = R * 0.055f * (1 + 0.4f * max(0f, sin(phase * TAU)))
                    x.fillStyle = Color.WHITE
                    x.beginPath()
                    x.arc((i - 1) * R * 0.18f, 0f, dotR, 0f, TAU)
                    x.fill()
                }
            }
            BadgeKind.BANG, BadgeKind.QUESTION -> {
                disc(R * 0.3f, Color.BLACK)
                disc(R * 0.23f, col)
                if (!isMini) {
                    x.fillStyle = Color.WHITE
                    x.font(900, R * 0.32f)
                    x.textAlign = Paint.Align.CENTER
                    x.textMiddle = true
                    x.fillText(if (kind == BadgeKind.BANG) "!" else "?", 0f, R * 0.02f)
                }
            }
            BadgeKind.DOT -> {
                disc(R * 0.2f, Color.BLACK)
                disc(R * 0.135f, col)
            }
        }
        x.restore()
    }

    private fun drawParticles(x: Ctx2D, R: Float, cx: Float, cy: Float) {
        for (q in particles) {
            if (q.age <= 0) continue
            val f = q.age / q.life
            val a = if (f < 0.2f) f / 0.2f else 1 - (f - 0.2f) / 0.8f
            val px = cx + (q.x + q.vx * q.age) * R * 1.3f
            val py = cy + (q.y + q.vy * q.age) * R * 1.3f
            val sz = R * q.size * (1 + f * 0.4f)

            x.save()
            x.translate(px, py)
            x.globalAlpha = min(1f, max(0f, a))
            when (q.type) {
                Bit.HEART -> {
                    x.rotate(sin(q.age * 6) * 0.3f)
                    x.fillStyle = HEART
                    heartPath(x, sz)
                    x.fill()
                }
                Bit.STAR -> {
                    x.rotate(q.rot + q.age * 2)
                    x.fillStyle = STAR
                    starPath(x, sz, sz * 0.45f)
                    x.fill()
                }
                Bit.SPARK -> {
                    x.rotate(q.rot)
                    x.fillStyle = Color.WHITE
                    starPath(x, sz * 0.8f, sz * 0.18f)
                    x.fill()
                }
                Bit.SWEAT -> {
                    x.fillStyle = SWEAT
                    x.beginPath()
                    x.moveTo(0f, -sz)
                    x.quadraticCurveTo(sz * 0.8f, sz * 0.2f, 0f, sz * 0.6f)
                    x.quadraticCurveTo(-sz * 0.8f, sz * 0.2f, 0f, -sz)
                    x.fill()
                }
                Bit.Z -> {
                    x.fillStyle = ZED
                    x.font(700, sz * 1.9f)
                    x.textAlign = Paint.Align.CENTER
                    x.textMiddle = true
                    x.fillText("z", 0f, 0f)
                }
            }
            x.restore()
        }
    }
}
