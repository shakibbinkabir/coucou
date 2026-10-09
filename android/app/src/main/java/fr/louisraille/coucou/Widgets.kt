// Coucou widgets: Solo, Team and List on the home screen — CoucouWidgets.swift.
// They show the sessions as the app last saw them, and are redrawn whenever a
// session changes.
//
// Each widget is drawn whole into a bitmap with the same Mochi as the app (an
// Android widget cannot run the app's drawing code any other way). So a tap
// anywhere on a widget opens the app on its most urgent session, where the
// iPhone's Team widget opens the Mochi that was tapped.

package fr.louisraille.coucou

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Bundle
import android.text.TextPaint
import android.text.TextUtils
import android.widget.RemoteViews
import androidx.compose.ui.graphics.toArgb

class SoloWidget : CoucouWidget()
class TeamWidget : CoucouWidget()
class ListWidget : CoucouWidget()

abstract class CoucouWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) Widgets.draw(context, manager, id, this::class.java)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        Widgets.draw(context, manager, id, this::class.java)
    }
}

object Widgets {
    private val kinds = listOf(SoloWidget::class.java, TeamWidget::class.java, ListWidget::class.java)

    /**
     * Who fills the Team's free spots, in this order, so the four are always
     * taken. The iPhone's regulars are mostly services; there are none here yet.
     */
    private val regulars = listOf("agent_codex", "agent_cursor", "agent_gemini", "agent_copilot")

    /** Redraws every widget on the home screen. */
    fun update(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        for (kind in kinds) {
            for (id in manager.getAppWidgetIds(ComponentName(context, kind))) draw(context, manager, id, kind)
        }
    }

    fun draw(context: Context, manager: AppWidgetManager, id: Int, kind: Class<*>) {
        val options = manager.getAppWidgetOptions(id)
        val wide = kind == ListWidget::class.java
        // Portrait: the narrowest width with the tallest height.
        val w = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: if (wide) 330 else 160
        val h = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 160
        // Most urgent first, like the app's list.
        val sessions = Link.sessions.sortedByUrgency()
        val bitmap = render(kind, w, h, context.resources.displayMetrics.density, sessions)

        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_PILL, sessions.firstOrNull()?.id)
        val views = RemoteViews(context.packageName, R.layout.widget)
        views.setImageViewBitmap(R.id.image, bitmap)
        views.setContentDescription(R.id.image, sessions.summary?.let { "Coucou, $it" } ?: "Coucou, all quiet")
        views.setOnClickPendingIntent(
            R.id.image,
            PendingIntent.getActivity(context, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
        )
        manager.updateAppWidget(id, views)
    }

    /** The picture of a widget of `kind`, `w` × `h` dp. */
    fun render(kind: Class<*>, w: Int, h: Int, density: Float, sessions: List<SessionItem>): Bitmap {
        val bitmap = Bitmap.createBitmap((w * density).toInt().coerceAtLeast(1), (h * density).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(density, density)
        // The system's own rounding, where the launcher does not clip.
        canvas.clipPath(android.graphics.Path().apply { addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), 24f, 24f, android.graphics.Path.Direction.CW) })
        // Idle Mochi take a new pose every half hour, when Android redraws the widgets by itself.
        val tick = (System.currentTimeMillis() / 1_800_000).toInt()
        when (kind) {
            SoloWidget::class.java -> solo(canvas, w.toFloat(), h.toFloat(), sessions.firstOrNull())
            TeamWidget::class.java -> team(canvas, w.toFloat(), h.toFloat(), sessions, tick)
            else -> list(canvas, w.toFloat(), h.toFloat(), sessions.take(4))
        }
        return bitmap
    }

    // ── Drawing ─────────────────────────────────────────────────────────────────

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val type = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)

    private fun white(w: Float, a: Float = 1f) = Color.argb((a * 255).toInt(), (w * 255).toInt(), (w * 255).toInt(), (w * 255).toInt())

    private fun toneColor(s: SessionItem): Int = when (s.tone) {
        "waiting" -> Ios.orange.toArgb()
        "question" -> Ios.cyan.toArgb()
        "error" -> Ios.red.toArgb()
        "done" -> Ios.green.toArgb()
        else -> white(1f, 0.7f)
    }

    /** One line of text, cut with an ellipsis at `maxWidth`. `y` is its baseline. Returns its width. */
    private fun text(
        canvas: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, weight: Int = 400,
        maxWidth: Float = Float.MAX_VALUE, alignRight: Boolean = false,
    ): Float {
        type.textSize = size
        type.color = color
        type.typeface = typeface(weight)
        val shown = TextUtils.ellipsize(s, type, maxWidth, TextUtils.TruncateAt.END).toString()
        val width = type.measureText(shown)
        canvas.drawText(shown, if (alignRight) x - width else x, y, type)
        return width
    }

    private fun mochi(
        canvas: Canvas, x: Float, y: Float, size: Float, state: BotState, bodyHex: String = "#FFFFFF",
        showBadge: Boolean = true, pose: MochiPose = MochiPose.neutral, alpha: Float = 1f,
    ) {
        val count = if (alpha < 1f) canvas.saveLayerAlpha(x, y, x + size, y + size, (alpha * 255).toInt()) else canvas.save()
        canvas.translate(x, y)
        BotEngine().apply {
            isMini = true
            bodyColor = hexToRGB(bodyHex)
            pose(state, pose.yaw, pose.pitch, pose.tilt, pose.open, pose.eye, showBadge)
        }.draw(Ctx2D().on(canvas), size, size)
        canvas.restoreToCount(count)
    }

    /** Solo: your most urgent agent session. */
    private fun solo(canvas: Canvas, w: Float, h: Float, session: SessionItem?) {
        val tint = hex(session?.color ?: "#3B4A6B")
        val from = Color.rgb((Color.red(tint) * 0.55f).toInt(), (Color.green(tint) * 0.55f).toInt(), (Color.blue(tint) * 0.55f).toInt())
        fill.shader = LinearGradient(0f, 0f, w, h, from, white(0.08f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w, h, fill)
        fill.shader = null

        val pad = 16f
        mochi(canvas, pad, pad, 56f, session?.state ?: BotState.SLEEPING)
        val width = w - pad * 2
        if (session == null) {
            text(canvas, "All quiet", pad, h - pad - 20f, 17f, Color.WHITE, 600, width)
            text(canvas, "No agent session", pad, h - pad - 3f, 12f, white(1f, 0.7f), maxWidth = width)
            return
        }
        text(canvas, session.title, pad, h - pad - 36f, 17f, Color.WHITE, 600, width)
        text(canvas, session.currentStep?.ifEmpty { null } ?: session.pillName, pad, h - pad - 19f, 12f, white(1f, 0.85f), maxWidth = width)
        val status = text(canvas, session.statusText, pad, h - pad - 3f, 11f, toneColor(session), maxWidth = width)
        text(canvas, " · ${relativeTime(session.updatedAt)}", pad + status, h - pad - 3f, 11f, white(1f, 0.8f), maxWidth = width - status)
    }

    /** Team: up to four agents at a glance, each Mochi in his own colour. */
    private fun team(canvas: Canvas, w: Float, h: Float, sessions: List<SessionItem>, tick: Int) {
        canvas.drawColor(white(0.08f))
        val members = sessions.take(4).toMutableList()
        for (id in regulars) {
            if (members.size >= 4) break
            if (members.any { it.id == id }) continue
            val pill = PillCatalog.definition(id) ?: continue
            members.add(regular(pill))
        }
        val pad = 12f
        val gap = 8f
        val tw = (w - pad * 2 - gap) / 2
        val th = (h - pad * 2 - gap) / 2
        for (i in 0 until 4) {
            val x = pad + (i % 2) * (tw + gap)
            val y = pad + (i / 2) * (th + gap)
            val member = members.getOrNull(i)
            fill.color = if (member != null) white(0.14f) else white(1f, 0.05f)
            canvas.drawRoundRect(x, y, x + tw, y + th, 16f, 16f, fill)
            val size = minOf(tw - 8f, th - 14f)
            if (member == null) {
                // A free spot: Mochi asleep, faded, instead of an empty square.
                mochi(canvas, x + (tw - size * 0.7f) / 2, y + (th - size * 0.7f) / 2, size * 0.7f, BotState.SLEEPING, showBadge = false,
                    pose = MochiPose(tilt = if ((tick + i) % 2 == 0) -0.06f else 0.06f), alpha = 0.18f)
                continue
            }
            // Calm Mochi look around; one who needs you keeps his state's face.
            val pose = if (member.isWaitingForYou || member.tone == "error") MochiPose.neutral else MochiPose.idle(member.id, tick)
            mochi(canvas, x + (tw - size) / 2, y + 2f, size, member.state, member.color, pose = pose)
            type.textSize = 9f
            type.typeface = typeface(600)
            val name = TextUtils.ellipsize(member.pillName, type, tw - 8f, TextUtils.TruncateAt.END).toString()
            text(canvas, name, x + (tw - type.measureText(name)) / 2, y + 2f + size - 2f, 9f, white(1f, 0.85f), 600)
            if (member.tone != "idle") {
                fill.color = toneColor(member)
                canvas.drawCircle(x + tw - 10f, y + 10f, 4f, fill)
            }
            if (member.isWaitingForYou) {
                // Waiting on you: the whole tile is outlined.
                stroke.color = toneColor(member)
                stroke.strokeWidth = 2f
                canvas.drawRoundRect(x + 1f, y + 1f, x + tw - 1f, y + th - 1f, 15f, 15f, stroke)
            }
        }
    }

    /** A calm Mochi from the catalog, for a free spot of the Team widget. */
    private fun regular(pill: PillDefinition) = SessionItem(
        id = pill.id, name = "", color = pill.color, state = BotState.IDLE, stepIndex = 0, steps = emptyList(),
        needsApproval = false, approvalCommand = "", approvalFingerprint = "", acceptsInstructions = false,
        question = "", questionPayload = null, questionFingerprint = "", finalLine = "", cwd = "", updatedAt = 0, macName = "",
    )

    /** List: your agent sessions and what they are doing. */
    private fun list(canvas: Canvas, w: Float, h: Float, sessions: List<SessionItem>) {
        canvas.drawColor(white(0.08f))
        val pad = 16f
        if (sessions.isEmpty()) {
            mochi(canvas, pad, (h - 34f) / 2, 34f, BotState.SLEEPING)
            text(canvas, "All quiet: no agent session", pad + 44f, h / 2 + 5f, 15f, white(1f, 0.7f), maxWidth = w - pad * 2 - 44f)
            return
        }
        var y = pad
        for (session in sessions) {
            fill.color = mochiTile(session.color).toArgb()
            canvas.drawRoundRect(pad, y, pad + 24f, y + 24f, 7f, 7f, fill)
            mochi(canvas, pad + 2f, y + 2f, 20f, session.state)
            val base = y + 17f
            val status = text(canvas, session.statusText, w - pad, base, 12f, toneColor(session), if (session.isWaitingForYou) 600 else 400, alignRight = true)
            val room = w - pad * 2 - 32f - status - 8f
            val title = text(canvas, session.title, pad + 32f, base, 15f, Color.WHITE, 600, room)
            if (room - title > 30f) text(canvas, session.pillName, pad + 32f + title + 8f, base, 12f, white(1f, 0.6f), maxWidth = room - title - 8f)
            y += 30f
        }
    }
}
