// Mochi on screen — MochiLive.swift and CoucouKit's MochiStill.swift.

package fr.louisraille.coucou

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlin.math.min

/**
 * Mochi alive: he blinks, looks around and reacts to his state (a roll when a
 * task finishes). Same engine as the Mac's notch. Draws only while on screen.
 */
@Composable
fun MochiLive(state: BotState, modifier: Modifier = Modifier, bodyHex: String = "#FFFFFF") {
    val engine = remember { BotEngine().apply { isMini = true } }
    val ctx = remember { Ctx2D() }
    var frame by remember { mutableLongStateOf(0L) }
    var last by remember { mutableLongStateOf(0L) }

    engine.bodyColor = remember(bodyHex) { hexToRGB(bodyHex) }
    LaunchedEffect(state) { engine.setState(state, force = frame == 0L) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { frame = it }
    }

    Canvas(modifier.aspectRatio(1f).clearAndSetSemantics {}) {
        val dt = if (last == 0L) 0f else min(0.05f, (frame - last) / 1e9f)
        last = frame
        engine.update(dt)
        drawIntoCanvas { engine.draw(ctx.on(it.nativeCanvas), size.width, size.height) }
    }
}

/**
 * One frame of the little life the mini Mochi have: looking around, blinking,
 * a happy squint. For surfaces that cannot animate (widgets, notifications).
 */
data class MochiPose(
    val yaw: Float = 0f, val pitch: Float = 0f, val tilt: Float = 0f, val open: Float = 1f, val eye: EyeShape? = null,
) {
    companion object {
        val neutral = MochiPose()

        private val idlePoses = listOf(
            MochiPose(),
            MochiPose(yaw = -0.45f),
            MochiPose(yaw = 0.45f),
            MochiPose(yaw = -0.3f, pitch = -0.25f),
            MochiPose(yaw = 0.35f, pitch = 0.2f, tilt = 0.08f),
            MochiPose(pitch = -0.3f, tilt = -0.06f),
            MochiPose(eye = EyeShape.CLOSED), // blink
            MochiPose(yaw = 0.2f, eye = EyeShape.HAPPY),
            MochiPose(yaw = -0.5f, tilt = -0.1f),
            MochiPose(yaw = 0.15f, pitch = 0.25f),
        )

        /**
         * A pose that differs per Mochi and per tick, the same every time for a
         * given pair (so a redraw does not jump).
         */
        fun idle(id: String, tick: Int): MochiPose {
            var hash = 5381UL
            for (byte in id.toByteArray()) hash = hash * 33UL + byte.toUByte().toULong()
            hash = hash * 31UL + (tick * 7919).toULong()
            hash = hash xor (hash shr 13)
            return idlePoses[(hash % idlePoses.size.toULong()).toInt()]
        }
    }
}

private fun stillEngine(state: BotState, eye: EyeShape?, bodyHex: String, showBadge: Boolean, pose: MochiPose) =
    BotEngine().apply {
        isMini = true
        bodyColor = hexToRGB(bodyHex)
        pose(state, pose.yaw, pose.pitch, pose.tilt, pose.open, eye ?: pose.eye, showBadge)
    }

/** Mochi in a fixed pose: one frame of the engine, no animation, no timer. */
@Composable
fun MochiStill(
    state: BotState = BotState.IDLE, modifier: Modifier = Modifier, eye: EyeShape? = null,
    bodyHex: String = "#FFFFFF", showBadge: Boolean = true, pose: MochiPose = MochiPose.neutral,
) {
    val engine = remember(state, eye, bodyHex, showBadge, pose) { stillEngine(state, eye, bodyHex, showBadge, pose) }
    val ctx = remember { Ctx2D() }
    Canvas(modifier.aspectRatio(1f).clearAndSetSemantics {}) {
        drawIntoCanvas { engine.draw(ctx.on(it.nativeCanvas), size.width, size.height) }
    }
}

/** The same still, as a bitmap of `size` pixels (widgets, notifications). */
fun mochiBitmap(
    size: Int, state: BotState, bodyHex: String = "#FFFFFF", showBadge: Boolean = true, pose: MochiPose = MochiPose.neutral,
): Bitmap {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    stillEngine(state, null, bodyHex, showBadge, pose)
        .draw(Ctx2D().on(android.graphics.Canvas(bitmap)), size.toFloat(), size.toFloat())
    return bitmap
}
