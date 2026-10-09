// What the app shows: sessions, questions, the last turn of each session, the
// decisions taken here. Ports of the iPhone's models (SessionItem in
// PhoneLink.swift, SessionStatus.swift, Decisions.swift, TurnArchive.swift) and
// of CoucouKit's (TurnSnapshot, QuestionPayload, PillCatalog).
//
// Dates are milliseconds since 1970 (System.currentTimeMillis()).

package fr.louisraille.coucou

import androidx.compose.ui.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Calendar
import kotlin.math.min

// ── Pills ─────────────────────────────────────────────────────────────────────
// IDs, names and colours are PillCatalog.swift's: contract values (hook routing,
// preferences), so an ID here never changes.

enum class PillCategory(val title: String) {
    WORKSPACE("Where you code"), AGENT("Agents"), AI("AI for the chat"), SERVICE("Services"),
}

class PillDefinition(val id: String, val name: String, val color: String, val category: PillCategory) {
    /** Label of what runs in a session of this pill (workspace and agent pills). */
    val sessionSubtitle: String
        get() = when (id) {
            "integration_claude" -> "Claude Code"
            "agent_cursor" -> "Cursor"
            "agent_codex" -> "Codex"
            "agent_hermes" -> "Hermes"
            "agent_claude-desktop" -> "Claude Desktop"
            else -> "Agent"
        }
}

object PillCatalog {
    const val MAIN = "integration_claude"

    val all = listOf(
        PillDefinition("integration_claude", "VS Code", "#F5F6F8", PillCategory.WORKSPACE),
        PillDefinition("agent_cursor", "Cursor", "#C0C4CC", PillCategory.WORKSPACE),
        PillDefinition("agent_antigravity", "Antigravity", "#E879F9", PillCategory.WORKSPACE),
        PillDefinition("agent_codex", "Codex", "#2DD4BF", PillCategory.WORKSPACE),
        PillDefinition("agent_gemini", "Gemini CLI", "#8AB4F8", PillCategory.AGENT),
        PillDefinition("agent_copilot", "Copilot CLI", "#818CF8", PillCategory.AGENT),
        PillDefinition("agent_muse", "Muse Code", "#38BDF8", PillCategory.AGENT),
        PillDefinition("agent_opencode", "OpenCode", "#4ADE80", PillCategory.AGENT),
        PillDefinition("agent_amp", "Amp", "#F59E0B", PillCategory.AGENT),
        PillDefinition("agent_hermes", "Hermes", "#C084FC", PillCategory.AGENT),
        PillDefinition("agent_claude-desktop", "Claude Desktop", "#D97757", PillCategory.AGENT),
        PillDefinition("integration_resend", "Resend", "#22C55E", PillCategory.SERVICE),
        PillDefinition("integration_n8n", "n8n", "#F29B38", PillCategory.SERVICE),
        PillDefinition("integration_vercel", "Vercel", "#7C5CFF", PillCategory.SERVICE),
        PillDefinition("integration_github", "GitHub", "#F4505E", PillCategory.SERVICE),
        PillDefinition("integration_notion", "Notion", "#8C8C8C", PillCategory.SERVICE),
        PillDefinition("integration_calcom", "Cal.com", "#C9956A", PillCategory.SERVICE),
        PillDefinition("integration_stripe", "Stripe", "#0570DE", PillCategory.SERVICE),
    )

    fun definition(id: String): PillDefinition? = all.find { it.id == id }

    /** A pill that carries agent sessions: a workspace or an agent, or any other tagged agent. */
    fun isSession(id: String): Boolean = when (definition(id)?.category) {
        PillCategory.WORKSPACE, PillCategory.AGENT -> true
        null -> id.startsWith("agent_")
        else -> false
    }

    /** Colours for agents outside the catalog, in order (SPEC §6). */
    private val fallbackColors = listOf("#F472B6", "#34D399", "#FB923C", "#60A5FA", "#E879F9")

    fun colorFor(id: String): String {
        definition(id)?.let { return it.color }
        var hash = 0
        for (ch in id) hash = hash * 31 + ch.code
        return fallbackColors[Math.floorMod(hash, fallbackColors.size)]
    }
}

/** `coucou_agent`: lowercase letters, digits and hyphens, 24 at most; "claude" is reserved. */
fun validateAgent(raw: String?): String? {
    val a = raw?.trim()?.lowercase() ?: return null
    if (a.isEmpty() || a.length > 24 || a == "claude") return null
    return if (a.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }) a else null
}

// ── Questions ─────────────────────────────────────────────────────────────────

/**
 * A question the agent asks (AskUserQuestion): the questions with their choices.
 * An answer carries the same fingerprint and the labels picked; it is only
 * taken if the labels are among the choices of the question still waiting.
 */
data class QuestionPayload(val items: List<Item>) {
    data class Option(val label: String, val description: String)
    data class Item(val question: String, val header: String, val options: List<Option>, val multiSelect: Boolean)

    /** Stable identifier of this exact question. */
    val fingerprint: String by lazy {
        val raw = items.joinToString("\u001E") { item ->
            (listOf(item.question, if (item.multiSelect) "multi" else "single") + item.options.map { it.label })
                .joinToString("\u001F")
        }
        MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /**
     * True when every pick is one of that question's choices, with one pick
     * per single-choice question.
     */
    fun accepts(selections: List<List<String>>): Boolean {
        if (selections.size != items.size) return false
        for ((item, picks) in items.zip(selections)) {
            val labels = item.options.map { it.label }.toSet()
            if (picks.isEmpty() || !picks.all { it in labels }) return false
            if (!item.multiSelect && picks.size != 1) return false
        }
        return true
    }

    fun toJson(): JSONObject = JSONObject().put("items", JSONArray(items.map { item ->
        JSONObject().put("question", item.question).put("header", item.header).put("multiSelect", item.multiSelect)
            .put("options", JSONArray(item.options.map { JSONObject().put("label", it.label).put("description", it.description) }))
    }))

    companion object {
        fun fromJson(o: JSONObject?): QuestionPayload? {
            val items = o?.optJSONArray("items") ?: return null
            return QuestionPayload(items.objects().map { item ->
                Item(
                    item.str("question"), item.str("header"),
                    (item.optJSONArray("options") ?: JSONArray()).objects().map { Option(it.str("label"), it.str("description")) },
                    item.optBoolean("multiSelect", false),
                )
            }).takeIf { it.items.isNotEmpty() }
        }

        /**
         * The questions of an AskUserQuestion `tool_input`, if all of them can be
         * shown as choices to pick from. Anything else is left to the terminal.
         */
        fun fromToolInput(input: JSONObject): QuestionPayload? {
            val raw = input.optJSONArray("questions") ?: return null
            val items = raw.objects().map { q ->
                val options = (q.optJSONArray("options") ?: JSONArray()).objects()
                    .filter { it.str("label").isNotEmpty() }
                    .map { Option(it.str("label"), it.str("description")) }
                if (q.str("question").isEmpty() || options.size < 2) return null
                Item(q.str("question"), q.str("header"), options, q.optBoolean("multiSelect", false))
            }
            return QuestionPayload(items).takeIf { items.isNotEmpty() && items.size == raw.length() }
        }
    }
}

// ── Sessions ──────────────────────────────────────────────────────────────────

/** An agent session, as the app shows it (SessionItem on the iPhone). */
data class SessionItem(
    /** Pill ID. */
    val id: String,
    val name: String,
    val color: String,
    val state: BotState,
    val stepIndex: Int,
    val steps: List<String>,
    val needsApproval: Boolean,
    val approvalCommand: String,
    /** Identifies the exact request; a decision is sent back with it. */
    val approvalFingerprint: String,
    /** The computer runs instructions sent from here for this session. */
    val acceptsInstructions: Boolean,
    val question: String,
    /** The question's choices, when it can be answered from here. */
    val questionPayload: QuestionPayload?,
    val questionFingerprint: String,
    val finalLine: String,
    val cwd: String,
    val updatedAt: Long,
    /** The computer the session runs on. */
    val macName: String,
) {
    val pillName: String get() = PillCatalog.definition(id)?.name ?: id.removePrefix("agent_").replaceFirstChar { it.uppercase() }
    val currentStep: String? get() = steps.getOrNull(stepIndex) ?: steps.lastOrNull()

    /** Lower = more urgent. Waiting on you first, then errors, then work, then rest. */
    val urgency: Int
        get() {
            if (needsApproval || state == BotState.APPROVAL) return 0
            if (question.isNotEmpty() || state == BotState.QUESTION) return 1
            return when (state) {
                BotState.ERROR, BotState.RATELIMIT, BotState.DIZZY -> 2
                BotState.WORKING, BotState.THINKING, BotState.SEARCHING -> 3
                BotState.FINISHED -> 4
                else -> 5
            }
        }

    val isWaitingForYou: Boolean get() = urgency <= 1
    val isWorking: Boolean get() = urgency == 3

    val statusText: String
        get() {
            if (needsApproval || state == BotState.APPROVAL) return "waiting for your OK"
            if (question.isNotEmpty() || state == BotState.QUESTION) return "has a question"
            return when (state) {
                BotState.WORKING, BotState.THINKING, BotState.SEARCHING ->
                    if (steps.isEmpty()) "working" else "working · ${min(stepIndex + 1, steps.size)}/${steps.size}"
                BotState.FINISHED -> "✓ done"
                BotState.ERROR -> "error"
                BotState.RATELIMIT -> "rate limited"
                BotState.SLEEPING -> "asleep"
                BotState.DIZZY -> "dizzy"
                else -> "idle"
            }
        }

    val statusColor: Color
        get() = when (urgency) {
            0 -> Ios.orange
            1 -> Ios.cyan
            2 -> Ios.red
            4 -> Ios.green
            else -> Ios.secondary
        }

    val title: String get() = name.ifEmpty { pillName }

    /** waiting, question, error, working, done, idle — what the widgets colour by. */
    val tone: String get() = listOf("waiting", "question", "error", "working", "done").getOrElse(urgency) { "idle" }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("color", color)
        .put("stepIndex", stepIndex).put("steps", JSONArray(steps))
        .put("finalLine", finalLine).put("cwd", cwd).put("updatedAt", updatedAt).put("macName", macName)

    companion object {
        /**
         * A session read back after a restart: what it was doing is over (its
         * requests died with the connection they came on), what it said stays.
         */
        fun fromJson(o: JSONObject): SessionItem {
            return SessionItem(
                id = o.str("id"), name = o.str("name"), color = o.str("color"), state = BotState.IDLE,
                stepIndex = o.optInt("stepIndex"), steps = (o.optJSONArray("steps") ?: JSONArray()).strings(),
                needsApproval = false, approvalCommand = "", approvalFingerprint = "", acceptsInstructions = false,
                question = "", questionPayload = null, questionFingerprint = "",
                finalLine = o.str("finalLine"), cwd = o.str("cwd"), updatedAt = o.optLong("updatedAt"), macName = o.str("macName"),
            )
        }
    }
}

fun List<SessionItem>.sortedByUrgency(): List<SessionItem> =
    sortedWith(compareBy<SessionItem> { it.urgency }.thenByDescending { it.updatedAt })

/** "2 working · 1 waiting", or null when nothing is going on. */
val List<SessionItem>.summary: String?
    get() {
        val waiting = count { it.isWaitingForYou }
        val working = count { it.isWorking }
        val parts = ArrayList<String>()
        if (working > 0) parts.add("$working working")
        if (waiting > 0) parts.add("$waiting waiting")
        return parts.joinToString(" · ").ifEmpty { null }
    }

/** The state Mochi shows in the header: the most urgent session's. */
val List<SessionItem>.leadState: BotState
    get() {
        val lead = sortedByUrgency().firstOrNull() ?: return BotState.SLEEPING
        if (lead.isWaitingForYou) return if (lead.needsApproval) BotState.APPROVAL else BotState.QUESTION
        return lead.state
    }

// ── The last turn ─────────────────────────────────────────────────────────────
// The prompt you sent, what the agent did (commands, reads, searches, edits
// with their diffs) and its final answer, built from the hook events
// (TurnRecorder). Only the latest turn per session is live; the ones before it
// go to the archive.

data class TurnAction(
    /** "Bash", "Edit", "Read"… */
    val tool: String,
    /** The command, file or pattern. */
    val summary: String,
    /** What a command printed (trimmed). */
    val output: String = "",
    val failed: Boolean = false,
    val date: Long,
    /** Index in `files` when this action changed a file. */
    val fileIndex: Int? = null,
)

enum class DiffKind { CONTEXT, ADDED, REMOVED, GAP }

data class TurnDiffLine(val kind: DiffKind, val text: String)

data class TurnFile(
    val path: String,
    val added: Int,
    val removed: Int,
    val isNew: Boolean,
    val lines: List<TurnDiffLine>,
    /** Some lines were left out to keep the turn small. */
    val truncated: Boolean = false,
) {
    val name: String get() = path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
}

data class TurnSnapshot(
    val pillId: String,
    val sessionId: String,
    val project: String,
    val prompt: String,
    val actions: List<TurnAction>,
    val files: List<TurnFile>,
    val finalMessage: String,
    val startedAt: Long,
    val endedAt: Long?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("pillId", pillId).put("sessionId", sessionId).put("project", project).put("prompt", prompt)
        .put("finalMessage", finalMessage).put("startedAt", startedAt).put("endedAt", endedAt ?: JSONObject.NULL)
        .put("actions", JSONArray(actions.map {
            JSONObject().put("tool", it.tool).put("summary", it.summary).put("output", it.output)
                .put("failed", it.failed).put("date", it.date).put("fileIndex", it.fileIndex ?: JSONObject.NULL)
        }))
        .put("files", JSONArray(files.map { f ->
            JSONObject().put("path", f.path).put("added", f.added).put("removed", f.removed)
                .put("isNew", f.isNew).put("truncated", f.truncated)
                .put("lines", JSONArray(f.lines.map { JSONArray().put(it.kind.ordinal).put(it.text) }))
        }))

    companion object {
        fun fromJson(o: JSONObject): TurnSnapshot = TurnSnapshot(
            pillId = o.str("pillId"), sessionId = o.str("sessionId"), project = o.str("project"),
            prompt = o.str("prompt"), finalMessage = o.str("finalMessage"),
            startedAt = o.optLong("startedAt"), endedAt = if (o.isNull("endedAt")) null else o.optLong("endedAt"),
            actions = (o.optJSONArray("actions") ?: JSONArray()).objects().map {
                TurnAction(
                    it.str("tool"), it.str("summary"), it.str("output"), it.optBoolean("failed"), it.optLong("date"),
                    if (it.isNull("fileIndex")) null else it.optInt("fileIndex"),
                )
            },
            files = (o.optJSONArray("files") ?: JSONArray()).objects().map { f ->
                val lines = f.optJSONArray("lines") ?: JSONArray()
                TurnFile(
                    f.str("path"), f.optInt("added"), f.optInt("removed"), f.optBoolean("isNew"),
                    (0 until lines.length()).mapNotNull { i ->
                        val line = lines.optJSONArray(i) ?: return@mapNotNull null
                        TurnDiffLine(DiffKind.entries.getOrElse(line.optInt(0)) { DiffKind.CONTEXT }, line.optString(1))
                    },
                    f.optBoolean("truncated"),
                )
            },
        )
    }
}

/**
 * The turns seen before the latest one (4 per session), and today's tally.
 * Built from the turns as they come, kept in a file on this phone only.
 */
data class TurnArchive(
    val past: Map<String, List<TurnSnapshot>> = emptyMap(),
    val today: DayTally = DayTally(),
) {
    /** Today: turns finished, files changed, lines added and removed. */
    data class DayTally(
        val day: Long = startOfToday(),
        val turns: Int = 0,
        val files: Int = 0,
        val added: Int = 0,
        val removed: Int = 0,
        /** "pillId|startedAt" of the turns counted, so none counts twice. */
        val counted: List<String> = emptyList(),
    ) {
        val isEmpty: Boolean get() = turns == 0
    }

    /** A new version of a session's latest turn arrived. */
    fun update(old: TurnSnapshot?, new: TurnSnapshot): TurnArchive {
        var next = this
        // A new turn started: the previous one joins the past turns.
        if (old != null && old.startedAt != new.startedAt && !(old.prompt.isEmpty() && old.actions.isEmpty())) {
            val list = listOf(old) + (past[old.pillId] ?: emptyList()).filter { it.startedAt != old.startedAt }
            next = next.copy(past = past + (old.pillId to list.take(KEPT_PER_SESSION)))
        }
        return next.count(new)
    }

    /** Adds a finished turn to today's tally, once. */
    private fun count(turn: TurnSnapshot): TurnArchive {
        val start = startOfToday()
        val tally = if (today.day != start) DayTally(start) else today
        val ended = turn.endedAt
        val key = "${turn.pillId}|${turn.startedAt}"
        if (ended == null || ended < start || key in tally.counted) return if (tally === today) this else copy(today = tally)
        return copy(
            today = tally.copy(
                counted = tally.counted + key,
                turns = tally.turns + 1,
                files = tally.files + turn.files.map { it.path }.toSet().size,
                added = tally.added + turn.files.sumOf { it.added },
                removed = tally.removed + turn.files.sumOf { it.removed },
            ),
        )
    }

    /** Today's tally, or an empty one after midnight. */
    val currentTally: DayTally get() = if (today.day == startOfToday()) today else DayTally()

    fun toJson(): JSONObject = JSONObject()
        .put("past", JSONObject().apply { for ((id, list) in past) put(id, JSONArray(list.map { it.toJson() })) })
        .put("today", JSONObject().put("day", today.day).put("turns", today.turns).put("files", today.files)
            .put("added", today.added).put("removed", today.removed).put("counted", JSONArray(today.counted)))

    companion object {
        const val KEPT_PER_SESSION = 4

        fun fromJson(o: JSONObject): TurnArchive {
            val past = o.optJSONObject("past") ?: JSONObject()
            val t = o.optJSONObject("today") ?: JSONObject()
            return TurnArchive(
                past.keys().asSequence().associateWith { id -> past.optJSONArray(id)!!.objects().map(TurnSnapshot::fromJson) },
                DayTally(
                    t.optLong("day"), t.optInt("turns"), t.optInt("files"), t.optInt("added"), t.optInt("removed"),
                    (t.optJSONArray("counted") ?: JSONArray()).strings(),
                ),
            )
        }
    }
}

fun startOfToday(): Long = Calendar.getInstance().apply {
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

// ── Decisions ─────────────────────────────────────────────────────────────────

enum class Decision { ALLOW, DENY }

/** One decision taken on this phone (kept on the phone only). */
data class DecisionLog(
    val decision: Decision,
    val pillId: String,
    /** The command, shortened. */
    val summary: String,
    val date: Long,
) {
    fun toJson(): JSONObject = JSONObject().put("decision", decision.name).put("pillId", pillId).put("summary", summary).put("date", date)

    companion object {
        fun fromJson(o: JSONObject) = DecisionLog(
            if (o.str("decision") == "ALLOW") Decision.ALLOW else Decision.DENY, o.str("pillId"), o.str("summary"), o.optLong("date"),
        )
    }
}

// ── JSON helpers ──────────────────────────────────────────────────────────────

/** Text of a JSON field, or "" — org.json's optString turns null into "null". */
fun JSONObject.str(name: String): String = if (isNull(name)) "" else optString(name, "")

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONArray.strings(): List<String> = (0 until length()).map { optString(it) }
