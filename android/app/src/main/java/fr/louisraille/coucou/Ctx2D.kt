// A Canvas-2D-shaped face on android.graphics.Canvas.
//
// Mochi, his outfits and the island are drawn on Windows and Linux with the
// browser's Canvas 2D API (windows/src/mochi), itself a port of the Mac's Swift.
// This keeps the same calls — save/restore with the whole drawing state,
// beginPath/arc/fill, gradients with colour stops — so the drawing code here
// reads line for line like the code it is ported from.

package fr.louisraille.coucou

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ── Colours (ARGB ints) ───────────────────────────────────────────────────────

fun rgb(r: Int, g: Int, b: Int): Int = Color.rgb(r, g, b)

/** CSS rgba(r,g,b,a): components 0…255, alpha 0…1. */
fun rgba(r: Int, g: Int, b: Int, a: Float): Int =
    Color.argb((a.coerceIn(0f, 1f) * 255).roundToInt(), r, g, b)

/** "#RRGGBB" (or "RRGGBB") → opaque colour. Anything unreadable is white. */
fun hex(s: String): Int = try {
    Color.parseColor(if (s.startsWith("#")) s else "#$s") or (0xFF shl 24)
} catch (_: IllegalArgumentException) {
    Color.WHITE
}

/** `color` with its alpha multiplied by `a`. */
fun withAlpha(color: Int, a: Float): Int =
    (color and 0x00FFFFFF) or ((Color.alpha(color) * a.coerceIn(0f, 1f)).roundToInt() shl 24)

/** A colour as three components 0…1, the engine's `RGB`. */
typealias RGB = FloatArray

fun rgbOf(r: Float, g: Float, b: Float): RGB = floatArrayOf(r, g, b)

fun hexToRGB(s: String): RGB {
    val c = hex(s)
    return floatArrayOf(Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f)
}

fun rgba(c: RGB, a: Float = 1f): Int =
    rgba((c[0] * 255).roundToInt(), (c[1] * 255).roundToInt(), (c[2] * 255).roundToInt(), a)

fun mix3(a: RGB, b: RGB, t: Float): RGB =
    floatArrayOf(lerp(a[0], b[0], t), lerp(a[1], b[1], t), lerp(a[2], b[2], t))

// ── Paths (Path2D is android.graphics.Path) ───────────────────────────────────

private fun deg(rad: Float) = rad * 180f / PI_F

/**
 * Canvas 2D `ellipse()`: an arc of the ellipse centred on (x, y), from angle
 * `a0` to `a1` (radians, clockwise unless `ccw`), joined to the current point
 * by a line when the path already has one. A whole turn adds the full oval.
 */
fun Path.ellipse(
    x: Float, y: Float, rx: Float, ry: Float, rotation: Float,
    a0: Float, a1: Float, ccw: Boolean = false,
) {
    if (rotation != 0f) {
        val tmp = Path()
        tmp.ellipse(x, y, rx, ry, 0f, a0, a1, ccw)
        tmp.transform(Matrix().apply { setRotate(deg(rotation), x, y) })
        addPath(tmp)
        return
    }
    var sweep = a1 - a0
    val full: Boolean
    if (!ccw) {
        full = sweep >= TAU - 1e-4f
        if (!full) sweep = ((sweep % TAU) + TAU) % TAU
    } else {
        full = -sweep >= TAU - 1e-4f
        if (!full) sweep = -(((-sweep % TAU) + TAU) % TAU)
    }
    if (full) addOval(x - rx, y - ry, x + rx, y + ry, Path.Direction.CW)
    else arcTo(x - rx, y - ry, x + rx, y + ry, deg(a0), deg(sweep), false)
}

/** Canvas 2D `arc()`. */
fun Path.arc(x: Float, y: Float, r: Float, a0: Float, a1: Float, ccw: Boolean = false) =
    ellipse(x, y, r, r, 0f, a0, a1, ccw)

/** A rounded rectangle as its own contour (the sources' `roundRectPath`). */
fun Path.roundRect(x: Float, y: Float, w: Float, h: Float, r: Float) {
    val rr = max(0f, min(r, min(w / 2, h / 2)))
    addRoundRect(x, y, x + w, y + h, rr, rr, Path.Direction.CW)
}

// ── Gradients ─────────────────────────────────────────────────────────────────

class Gradient internal constructor(
    private val linear: Boolean,
    private val x0: Float, private val y0: Float,
    private val x1: Float, private val y1: Float,
    private val r0: Float, private val r1: Float,
) {
    private val colors = ArrayList<Int>(4)
    private val stops = ArrayList<Float>(4)
    private var shader: Shader? = null

    fun addColorStop(offset: Float, color: Int): Gradient {
        stops.add(offset.coerceIn(0f, 1f))
        colors.add(color)
        shader = null
        return this
    }

    internal fun shader(): Shader {
        shader?.let { return it }
        // A gradient needs two stops to be one.
        if (colors.isEmpty()) addColorStop(0f, Color.TRANSPARENT)
        if (colors.size == 1) addColorStop(1f, colors[0])
        val c = colors.toIntArray()
        val made: Shader = if (linear) {
            LinearGradient(x0, y0, x1, y1, c, stops.toFloatArray(), Shader.TileMode.CLAMP)
        } else {
            // Both circles share a centre everywhere in the sources: the inner
            // radius becomes an offset of the stops.
            val k = if (r1 > 0f) r0 / r1 else 0f
            val s = FloatArray(stops.size) { k + stops[it] * (1 - k) }
            RadialGradient(x1, y1, max(r1, 0.001f), c, s, Shader.TileMode.CLAMP)
        }
        shader = made
        return made
    }
}

// ── Context ───────────────────────────────────────────────────────────────────

class Ctx2D {
    lateinit var canvas: Canvas

    /** A colour (Int, ARGB) or a [Gradient]. */
    var fillStyle: Any = Color.BLACK
    var strokeStyle: Any = Color.BLACK
    var lineWidth = 1f
    var lineCap = Paint.Cap.BUTT
    var lineJoin = Paint.Join.MITER
    var globalAlpha = 1f
    var fontSize = 10f
    var fontWeight = 400
    var textAlign = Paint.Align.LEFT
    /** textBaseline = "middle" (false: alphabetic). */
    var textMiddle = false

    /** The current path, as begun by [beginPath]. */
    var path = Path()
        private set

    private class Saved(
        val fill: Any, val stroke: Any, val lineWidth: Float, val cap: Paint.Cap, val join: Paint.Join,
        val alpha: Float, val fontSize: Float, val fontWeight: Int, val align: Paint.Align, val middle: Boolean,
    )

    private val stack = ArrayList<Saved>()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)

    /** Starts drawing on `c` with a fresh state. */
    fun on(c: Canvas): Ctx2D {
        canvas = c
        stack.clear()
        fillStyle = Color.BLACK
        strokeStyle = Color.BLACK
        lineWidth = 1f
        lineCap = Paint.Cap.BUTT
        lineJoin = Paint.Join.MITER
        globalAlpha = 1f
        textAlign = Paint.Align.LEFT
        textMiddle = false
        path = Path()
        return this
    }

    // State

    fun save() {
        canvas.save()
        stack.add(Saved(fillStyle, strokeStyle, lineWidth, lineCap, lineJoin, globalAlpha, fontSize, fontWeight, textAlign, textMiddle))
    }

    fun restore() {
        canvas.restore()
        val s = stack.removeLastOrNull() ?: return
        fillStyle = s.fill
        strokeStyle = s.stroke
        lineWidth = s.lineWidth
        lineCap = s.cap
        lineJoin = s.join
        globalAlpha = s.alpha
        fontSize = s.fontSize
        fontWeight = s.fontWeight
        textAlign = s.align
        textMiddle = s.middle
    }

    /**
     * Draws `block` into a layer of its own, composited at `alpha` — what
     * GraphicsContext.drawLayer does on the Mac, so overlapping shapes of one
     * accessory do not show through each other while it fades.
     */
    inline fun layer(alpha: Float, block: () -> Unit) {
        if (alpha >= 0.999f) {
            block()
            return
        }
        val count = canvas.saveLayerAlpha(null, (alpha.coerceIn(0f, 1f) * 255).toInt())
        block()
        canvas.restoreToCount(count)
    }

    fun translate(x: Float, y: Float) = canvas.translate(x, y)
    fun rotate(rad: Float) = canvas.rotate(rad * 180f / PI_F)
    fun scale(x: Float, y: Float) = canvas.scale(x, y)

    // Path

    fun beginPath() {
        path = Path()
    }

    fun moveTo(x: Float, y: Float) = path.moveTo(x, y)

    /** Like Canvas 2D, a lineTo on an empty path is a moveTo. */
    fun lineTo(x: Float, y: Float) {
        if (path.isEmpty) path.moveTo(x, y) else path.lineTo(x, y)
    }

    fun quadraticCurveTo(cx: Float, cy: Float, x: Float, y: Float) = path.quadTo(cx, cy, x, y)
    fun bezierCurveTo(c1x: Float, c1y: Float, c2x: Float, c2y: Float, x: Float, y: Float) =
        path.cubicTo(c1x, c1y, c2x, c2y, x, y)

    fun arc(x: Float, y: Float, r: Float, a0: Float, a1: Float, ccw: Boolean = false) =
        path.arc(x, y, r, a0, a1, ccw)

    fun ellipse(x: Float, y: Float, rx: Float, ry: Float, rotation: Float, a0: Float, a1: Float, ccw: Boolean = false) =
        path.ellipse(x, y, rx, ry, rotation, a0, a1, ccw)

    fun rect(x: Float, y: Float, w: Float, h: Float) = path.addRect(x, y, x + w, y + h, Path.Direction.CW)

    /** beginPath + a rounded rectangle: the sources' `roundRectPath(x, …)`. */
    fun roundRectPath(x: Float, y: Float, w: Float, h: Float, r: Float) {
        beginPath()
        path.roundRect(x, y, w, h, r)
    }

    fun closePath() = path.close()

    // Painting

    private fun paint(p: Paint, style: Any): Paint {
        if (style is Gradient) {
            p.shader = style.shader()
            p.color = Color.WHITE
            p.alpha = (255 * globalAlpha.coerceIn(0f, 1f)).roundToInt()
        } else {
            val c = style as Int
            p.shader = null
            p.color = c
            p.alpha = (Color.alpha(c) * globalAlpha.coerceIn(0f, 1f)).roundToInt()
        }
        return p
    }

    private fun strokePaint(): Paint {
        strokePaint.strokeWidth = lineWidth
        strokePaint.strokeCap = lineCap
        strokePaint.strokeJoin = lineJoin
        return paint(strokePaint, strokeStyle)
    }

    fun fill(p: Path = path) = canvas.drawPath(p, paint(fillPaint, fillStyle))
    fun stroke(p: Path = path) = canvas.drawPath(p, strokePaint())

    /** Clips to `p` (the current path by default); `evenOdd` as in clip(p, "evenodd"). */
    fun clip(p: Path = path, evenOdd: Boolean = false) {
        if (evenOdd) p.fillType = Path.FillType.EVEN_ODD
        canvas.clipPath(p)
    }

    fun fillRect(x: Float, y: Float, w: Float, h: Float) =
        canvas.drawRect(x, y, x + w, y + h, paint(fillPaint, fillStyle))

    fun createLinearGradient(x0: Float, y0: Float, x1: Float, y1: Float) =
        Gradient(true, x0, y0, x1, y1, 0f, 0f)

    fun createRadialGradient(x0: Float, y0: Float, r0: Float, x1: Float, y1: Float, r1: Float) =
        Gradient(false, x0, y0, x1, y1, r0, r1)

    // Text

    /** `font = "<weight> <size>px system-ui"`. */
    fun font(weight: Int, size: Float) {
        fontWeight = weight
        fontSize = size
    }

    private fun textPaint(): Paint {
        textPaint.textSize = fontSize
        textPaint.textAlign = textAlign
        textPaint.typeface = typeface(fontWeight)
        return paint(textPaint, fillStyle)
    }

    fun measureText(s: String): Float = textPaint().measureText(s)

    fun fillText(s: String, x: Float, y: Float) {
        val p = textPaint()
        val dy = if (textMiddle) -(p.ascent() + p.descent()) / 2 else 0f
        canvas.drawText(s, x, y + dy, p)
    }
}

private val typefaces = HashMap<Int, Typeface>()

/** The system font at a CSS weight (100…900). */
fun typeface(weight: Int): Typeface = typefaces.getOrPut(weight) {
    if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, weight, false)
    else Typeface.create(Typeface.DEFAULT, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
}
