// What to give the computer so its agents' hooks reach this phone, and how
// times read on screen.

package fr.louisraille.coucou

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object LinkSetup {
    /** The same events, with the same time limits, as the hooks the desktop apps install. */
    private val EVENTS = listOf(
        "SessionStart" to 10, "SessionEnd" to 10, "UserPromptSubmit" to 10, "PreToolUse" to 10,
        "PostToolUse" to 10, "PostToolUseFailure" to 10, "PermissionRequest" to 120, "Notification" to 10,
        "Stop" to 10, "StopFailure" to 10, "SubagentStart" to 10, "SubagentStop" to 10,
    )

    /** `http://192.168.0.21:47821`, or null when the phone is on no network. */
    fun baseUrl(): String? = Link.localAddress()?.let { "http://$it:${Prefs.port}" }

    /**
     * The command a hook runs: the event's JSON, as the agent gives it on
     * stdin, posted to this phone. `agent` tags the session for another agent
     * than Claude Code ("codex", "gemini"…).
     *
     * The agent is never blocked: curl gives up connecting after 0.4 s when the
     * phone is away. Only a permission request waits, for an answer or 112 s.
     */
    fun command(waits: Boolean, agent: String = ""): String {
        val url = (baseUrl() ?: "http://<phone address>:${Prefs.port}") + "/hook" + if (agent.isEmpty()) "" else "?agent=$agent"
        val limits = if (waits) "-m 112 --connect-timeout 1" else "-m 2 --connect-timeout 0.4"
        return "curl -s $limits -H \"Authorization: Bearer ${Prefs.linkToken()}\" " +
            "-H \"Content-Type: application/json\" --data-binary @- $url"
    }

    /**
     * The `hooks` block for Claude Code's settings.json (~/.claude/settings.json),
     * to merge with what is already there.
     */
    fun claudeHooksJson(): String {
        val hooks = JSONObject()
        for ((event, timeout) in EVENTS) {
            val waits = event == "PermissionRequest"
            val hook = JSONObject().put("type", "command").put("command", command(waits)).put("timeout", timeout)
            // Nothing but a permission request has an answer worth waiting for.
            if (!waits) hook.put("async", true)
            hooks.put(event, JSONArray().put(JSONObject().put("hooks", JSONArray().put(hook))))
        }
        return JSONObject().put("hooks", hooks).toString(2).replace("\\/", "/")
    }

    /** A command to run on the computer to check that it reaches the phone: it prints {"app":"coucou"}. */
    fun testCommand(): String =
        "curl -s -m 3 -H \"Authorization: Bearer ${Prefs.linkToken()}\" ${baseUrl() ?: "http://<phone address>:${Prefs.port}"}/ping"
}

// ── Times ─────────────────────────────────────────────────────────────────────

/** "7 secs", "1 min, 0 secs", "2 hrs, 5 min", "1 day, 7 hrs" — SwiftUI's Text(date, style: .relative). */
fun relativeTime(date: Long, now: Long = System.currentTimeMillis()): String {
    val s = kotlin.math.abs(now - date) / 1000
    fun n(v: Long, one: String, many: String) = "$v ${if (v == 1L) one else many}"
    return when {
        s < 60 -> n(s, "sec", "secs")
        s < 3600 -> "${n(s / 60, "min", "min")}, ${n(s % 60, "sec", "secs")}"
        s < 86_400 -> "${n(s / 3600, "hr", "hrs")}, ${n(s % 3600 / 60, "min", "min")}"
        else -> "${n(s / 86_400, "day", "days")}, ${n(s % 86_400 / 3600, "hr", "hrs")}"
    }
}

/** "21:20" (or the phone's own way of writing the time). */
fun clockTime(date: Long): String = SimpleDateFormat.getTimeInstance(SimpleDateFormat.SHORT).format(Date(date))

/** "Today", "Yesterday", or "Monday, 6 October". */
fun dayLabel(date: Long): String {
    val day = Calendar.getInstance().apply { timeInMillis = date }
    val today = Calendar.getInstance()
    fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    if (same(day, today)) return "Today"
    today.add(Calendar.DAY_OF_YEAR, -1)
    if (same(day, today)) return "Yesterday"
    return SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date(date))
}

/** A time since `date` that counts on its own, second by second. */
@Composable
fun RelativeTime(date: Long, style: TextStyle, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    Text(relativeTime(date, now), style = style, modifier = modifier, maxLines = 1)
}
