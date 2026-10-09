// Easing + spring helpers — port of windows/src/core/anim.ts.
// Ease.* mirrors BotEngine.swift `enum Ease` (itself the prototype's `E`).
// Spring mirrors SwiftUI `.spring(response:dampingFraction:)` so open/close motion
// matches the macOS app.

package fr.louisraille.coucou

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

typealias EaseFn = (Float) -> Float

object Ease {
    val out: EaseFn = { t -> 1f - (1f - t).pow(3) }
    val inOut: EaseFn = { t -> if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).pow(3) / 2f }
    val back: EaseFn = { t ->
        val c1 = 1.7f
        val c3 = c1 + 1f
        1f + c3 * (t - 1f).pow(3) + c1 * (t - 1f).pow(2)
    }
    val lin: EaseFn = { t -> t }
    val easeIn: EaseFn = { t -> t * t * t }
}

fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
fun clamp(v: Float, lo: Float, hi: Float) = max(lo, min(hi, v))
fun seg(t: Float, a: Float, b: Float) = clamp((t - a) / (b - a), 0f, 1f)

const val PI_F = PI.toFloat()
const val TAU = (PI * 2).toFloat()

/** Seconds on the clock every animation runs on (performance.now() / 1000). */
fun now(): Double = SystemClock.uptimeMillis() / 1000.0
fun nowMs(): Long = SystemClock.uptimeMillis()

val mainHandler = Handler(Looper.getMainLooper())

/** setTimeout. Returns the Runnable, to hand to [cancel]. */
fun after(ms: Long, block: () -> Unit): Runnable {
    val r = Runnable(block)
    mainHandler.postDelayed(r, ms)
    return r
}

fun cancel(r: Runnable?) {
    if (r != null) mainHandler.removeCallbacks(r)
}

/** cubic-bezier(x1,y1,x2,y2) — used for the 340 ms close curve (.45,0,.2,1). */
fun cubicBezier(x1: Float, y1: Float, x2: Float, y2: Float): EaseFn {
    fun cx(t: Float) = (1 - t).pow(2) * 3 * t * x1 + 3 * (1 - t) * t * t * x2 + t.pow(3)
    fun cy(t: Float) = (1 - t).pow(2) * 3 * t * y1 + 3 * (1 - t) * t * t * y2 + t.pow(3)
    return { x ->
        var lo = 0f
        var hi = 1f
        var t = x
        repeat(12) {
            if (cx(t) < x) lo = t else hi = t
            t = (lo + hi) / 2
        }
        cy(t)
    }
}

val closeCurve = cubicBezier(0.45f, 0f, 0.2f, 1f)

/**
 * SwiftUI-equivalent spring: ω₀ = 2π / response, ζ = dampingFraction.
 * Integrated per frame (sub-stepped) so a dropped frame never destabilises it.
 */
class Spring(var value: Float, response: Float = 0.5f, damping: Float = 0.72f) {
    var target = value
    var velocity = 0f
    private var omega = TAU / response
    private var zeta = damping

    fun configure(response: Float, damping: Float) {
        omega = TAU / response
        zeta = damping
    }

    fun set(v: Float) {
        value = v
        target = v
        velocity = 0f
    }

    val settled: Boolean
        get() = abs(target - value) < 0.01f && abs(velocity) < 0.05f

    fun step(dt: Float) {
        val steps = max(1, ceil(dt / (1f / 240f)).toInt())
        val h = dt / steps
        repeat(steps) {
            val acc = omega * omega * (target - value) - 2 * zeta * omega * velocity
            velocity += acc * h
            value += velocity * h
        }
    }
}

/**
 * Value driven either by a spring (growing) or a timed curve (shrinking) —
 * matches IslandContainer: openSpring for grow, closeEase 340 ms for shrink.
 */
class Tracked(value: Float) {
    private val spring = Spring(value)
    private var curveFrom = 0f
    private var curveTo = 0f
    private var curveStart = 0L
    private var curveDur = 0L
    private var mode = 0 // 0 idle, 1 spring, 2 curve

    val value: Float get() = spring.value
    val target: Float get() = spring.target
    val animating: Boolean get() = mode != 0

    fun jump(v: Float) {
        spring.set(v)
        mode = 0
    }

    /** Spring to `v` (open / grow). */
    fun springTo(v: Float, response: Float = 0.5f, damping: Float = 0.72f) {
        spring.configure(response, damping)
        spring.target = v
        mode = 1
    }

    /** Timed curve to `v` (close / shrink), no overshoot. */
    fun curveTowards(v: Float, durationMs: Long = 340) {
        curveFrom = spring.value
        curveTo = v
        curveStart = nowMs()
        curveDur = durationMs
        spring.target = v
        spring.velocity = 0f
        mode = 2
    }

    fun step(dt: Float) {
        if (mode == 1) {
            spring.step(dt)
            if (spring.settled) {
                spring.value = spring.target
                spring.velocity = 0f
                mode = 0
            }
        } else if (mode == 2) {
            val p = clamp((nowMs() - curveStart).toFloat() / curveDur, 0f, 1f)
            spring.value = lerp(curveFrom, curveTo, closeCurve(p))
            if (p >= 1f) {
                spring.velocity = 0f
                mode = 0
            }
        }
    }
}
