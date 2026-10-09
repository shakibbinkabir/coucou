// The last turn of each session, built from the hook events — port of the Mac's
// TurnRecorder.swift, with CoucouKit's DiffEngine for the edits.
//
// UserPromptSubmit starts a turn, Pre/PostToolUse add actions (commands with
// their output, edits with their diff), Stop adds the final answer. On the Mac
// this runs next to the agent and the turn travels to the iPhone; here the
// hook events arrive on the phone, so the phone records the turn itself.

package fr.louisraille.coucou

import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

// ── DiffEngine ────────────────────────────────────────────────────────────────

/** A file change: its counts and its hunks (changed lines with 3 lines of context). */
class FileDiff(
    val added: Int,
    val removed: Int,
    val hunks: List<List<TurnDiffLine>>,
    /** Too big to diff line by line: only the counts are known. */
    val tooLarge: Boolean,
    val isNewFile: Boolean,
)

object DiffEngine {
    private const val MAX_BYTES = 200 * 1024
    private const val MAX_LINES = 4000

    fun fromEdit(old: String, new: String): FileDiff {
        if (old.toByteArray().size + new.toByteArray().size > MAX_BYTES) return countFallback(old, new)
        val oldLines = splitLines(old)
        val newLines = splitLines(new)
        if (oldLines.size + newLines.size > MAX_LINES) return countFallback(old, new)
        // LCS is O(m·n) — bail out before the quadratic blow-up.
        if (oldLines.size.toLong() * newLines.size > 1_000_000) return countFallback(old, new)
        val flat = diffLines(oldLines, newLines)
        return FileDiff(
            flat.count { it.kind == DiffKind.ADDED }, flat.count { it.kind == DiffKind.REMOVED },
            hunks(flat, context = 3), tooLarge = false, isNewFile = false,
        )
    }

    fun fromNew(content: String): FileDiff {
        if (content.toByteArray().size > MAX_BYTES) {
            return FileDiff(content.split("\n").size, 0, emptyList(), tooLarge = true, isNewFile = true)
        }
        val lines = splitLines(content)
        if (lines.size > MAX_LINES) return FileDiff(lines.size, 0, emptyList(), tooLarge = true, isNewFile = true)
        val added = lines.map { TurnDiffLine(DiffKind.ADDED, it) }
        return FileDiff(added.size, 0, if (added.isEmpty()) emptyList() else listOf(added), tooLarge = false, isNewFile = true)
    }

    private fun splitLines(text: String): List<String> {
        val parts = text.replace("\r\n", "\n").split("\n")
        // Drop the empty element a trailing newline leaves.
        return if (parts.last().isEmpty()) parts.dropLast(1) else parts
    }

    /** The lines of both sides in order: kept, removed, added (longest common subsequence). */
    private fun diffLines(old: List<String>, new: List<String>): List<TurnDiffLine> {
        val m = old.size
        val n = new.size
        // dp[i][j] = LCS length of old[0..<i] and new[0..<j]
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) for (j in 1..n) {
            dp[i][j] = if (old[i - 1] == new[j - 1]) dp[i - 1][j - 1] + 1 else max(dp[i - 1][j], dp[i][j - 1])
        }
        // Backtrack to the matching pairs.
        val matches = ArrayList<Pair<Int, Int>>()
        var i = m
        var j = n
        while (i > 0 && j > 0) {
            if (old[i - 1] == new[j - 1]) {
                matches.add(i - 1 to j - 1)
                i--
                j--
            } else if (dp[i - 1][j] >= dp[i][j - 1]) {
                i--
            } else {
                j--
            }
        }
        matches.reverse()

        val result = ArrayList<TurnDiffLine>()
        var prevOld = -1
        var prevNew = -1
        for ((oi, ni) in matches) {
            for (k in prevOld + 1 until oi) result.add(TurnDiffLine(DiffKind.REMOVED, old[k]))
            for (k in prevNew + 1 until ni) result.add(TurnDiffLine(DiffKind.ADDED, new[k]))
            result.add(TurnDiffLine(DiffKind.CONTEXT, old[oi]))
            prevOld = oi
            prevNew = ni
        }
        for (k in prevOld + 1 until m) result.add(TurnDiffLine(DiffKind.REMOVED, old[k]))
        for (k in prevNew + 1 until n) result.add(TurnDiffLine(DiffKind.ADDED, new[k]))
        return result
    }

    /** The changed lines with `context` lines around them; ranges that touch are merged. */
    private fun hunks(lines: List<TurnDiffLine>, context: Int): List<List<TurnDiffLine>> {
        val merged = ArrayList<IntArray>()
        lines.forEachIndexed { index, line ->
            if (line.kind == DiffKind.CONTEXT) return@forEachIndexed
            val start = max(0, index - context)
            val end = min(lines.size - 1, index + context)
            val last = merged.lastOrNull()
            if (last != null && start <= last[1] + 1) last[1] = max(last[1], end) else merged.add(intArrayOf(start, end))
        }
        return merged.map { lines.subList(it[0], it[1] + 1).toList() }
    }

    private fun countFallback(old: String, new: String): FileDiff {
        val oldLines = old.split("\n")
        val newLines = new.split("\n")
        val oldSet = oldLines.toSet()
        val newSet = newLines.toSet()
        return FileDiff(
            newLines.count { it.isNotEmpty() && it !in oldSet }, oldLines.count { it.isNotEmpty() && it !in newSet },
            emptyList(), tooLarge = true, isNewFile = false,
        )
    }

    /** The first useful paragraph of a Markdown answer, on one line (DiffEngine.toOneLine). */
    fun toOneLine(text: String, maxChars: Int = 200): String {
        val paragraphs = ArrayList<List<String>>()
        var current = ArrayList<String>()
        for (line in text.replace("\r\n", "\n").split("\n")) {
            val t = line.trim(' ', '\t')
            val isRule = t.length >= 3 && (t.all { it == '-' } || t.all { it == '*' } || t.all { it == '_' })
            if (t.isEmpty() || isRule || t.startsWith("|")) {
                if (current.isNotEmpty()) paragraphs.add(current)
                current = ArrayList()
            } else {
                current.add(line)
            }
        }
        if (current.isNotEmpty()) paragraphs.add(current)

        for (paragraph in paragraphs) {
            val words = ArrayList<String>()
            for (line in paragraph) {
                var l = line.replace("**", "").replace("__", "").replace("`", "").trim(' ', '\t').trimStart('#').trim(' ', '\t')
                l = if (l.startsWith("- ") || l.startsWith("* ") || l.startsWith("• ")) l.substring(2)
                else l.replace(Regex("^\\d+\\.\\s+"), "")
                words.addAll(l.split(Regex("\\s+")).filter { it.isNotEmpty() })
            }
            val collapsed = words.joinToString(" ")
            if (collapsed.isNotEmpty()) return collapsed.take(maxChars)
        }
        return ""
    }
}

// ── TurnRecorder ──────────────────────────────────────────────────────────────

object TurnRecorder {
    // Size limits: a turn stays small enough to keep and to draw.
    private const val MAX_ACTIONS = 200
    private const val MAX_LINES_PER_FILE = 600
    private const val MAX_TOTAL_LINES = 4000
    private const val MAX_OUTPUT = 1500
    private const val MAX_FINAL = 12_000

    /**
     * Called for every event of a session pill. Returns the pill's turn when
     * the event changed it, null otherwise.
     */
    fun record(event: String, payload: JSONObject, pillId: String, latest: TurnSnapshot?): TurnSnapshot? {
        val sessionId = payload.str("session_id").ifEmpty { payload.str("conversation_id") }
        val cwd = payload.str("cwd")
        val now = System.currentTimeMillis()

        /** The turn in progress, or a new one when the prompt was not seen (Codex, app started mid-turn). */
        fun current(): TurnSnapshot =
            if (latest != null && latest.endedAt == null) latest
            else TurnSnapshot(pillId, sessionId, projectOf(cwd), "", emptyList(), emptyList(), "", now, null)

        when (event) {
            "UserPromptSubmit" ->
                return TurnSnapshot(pillId, sessionId, projectOf(cwd), payload.str("prompt").take(8000), emptyList(), emptyList(), "", now, null)

            "PreToolUse" -> {
                val tool = payload.str("tool_name").ifEmpty { "Tool" }
                if (tool == "AskUserQuestion") return null
                val turn = current()
                if (turn.actions.size >= MAX_ACTIONS) return null
                val input = payload.optJSONObject("tool_input") ?: JSONObject()
                return turn.copy(actions = turn.actions + TurnAction(tool, summary(input), date = now))
            }

            "PostToolUse", "PostToolUseFailure" -> {
                val tool = payload.str("tool_name").ifEmpty { "Tool" }
                if (tool == "AskUserQuestion") return null
                var turn = current()
                val input = payload.optJSONObject("tool_input") ?: JSONObject()
                val summary = summary(input)
                // The matching PreToolUse action, latest first; one is added if it was missed.
                var index = turn.actions.indexOfLast { it.tool == tool && it.summary == summary && it.output.isEmpty() && it.fileIndex == null }
                if (index < 0 && turn.actions.size < MAX_ACTIONS) {
                    turn = turn.copy(actions = turn.actions + TurnAction(tool, summary, date = now))
                    index = turn.actions.size - 1
                }
                if (index < 0) return null
                var action = turn.actions[index].copy(failed = event == "PostToolUseFailure", output = output(payload))
                var files = turn.files
                val diff = if (event == "PostToolUse") fileDiff(tool, input) else null
                if (diff != null) {
                    val used = files.sumOf { it.lines.size }
                    val budget = max(0, min(MAX_LINES_PER_FILE, MAX_TOTAL_LINES - used))
                    files = files + turnFile(input.str("file_path"), diff, budget)
                    action = action.copy(fileIndex = files.size - 1)
                }
                return turn.copy(actions = turn.actions.toMutableList().also { it[index] = action }, files = files)
            }

            "Stop", "StopFailure" -> {
                val final = payload.str("last_assistant_message").ifEmpty { payload.str("message") }
                return current().copy(finalMessage = final.take(MAX_FINAL), endedAt = now)
            }

            else -> return null
        }
    }

    private fun projectOf(cwd: String) = cwd.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')

    /** What the action is about: the command, file or pattern. */
    fun summary(input: JSONObject): String {
        for (key in listOf("command", "file_path", "path", "pattern", "url", "query", "description", "prompt")) {
            val value = input.opt(key) as? String
            if (!value.isNullOrEmpty()) return value.take(600)
        }
        return ""
    }

    /** What a command printed, or the error. */
    private fun output(payload: JSONObject): String {
        val response = payload.opt("tool_response")
        val text = when {
            payload.opt("error") is String -> payload.str("error")
            response is JSONObject -> listOf(response.str("stdout"), response.str("stderr")).filter { it.isNotEmpty() }.joinToString("\n")
            response is String -> response
            else -> ""
        }.trim()
        return if (text.length > MAX_OUTPUT) text.take(MAX_OUTPUT) + "\n…" else text
    }

    private fun fileDiff(tool: String, input: JSONObject): FileDiff? {
        if (input.opt("file_path") !is String) return null
        return when (tool) {
            "Edit" -> {
                val old = input.opt("old_string") as? String ?: return null
                val new = input.opt("new_string") as? String ?: return null
                DiffEngine.fromEdit(old, new)
            }
            "MultiEdit" -> {
                val edits = input.optJSONArray("edits") ?: return null
                val diffs = edits.objects().mapNotNull {
                    val old = it.opt("old_string") as? String ?: return@mapNotNull null
                    val new = it.opt("new_string") as? String ?: return@mapNotNull null
                    DiffEngine.fromEdit(old, new)
                }
                FileDiff(diffs.sumOf { it.added }, diffs.sumOf { it.removed }, diffs.flatMap { it.hunks }, diffs.any { it.tooLarge }, false)
            }
            "Write" -> DiffEngine.fromNew(input.opt("content") as? String ?: return null)
            else -> null
        }
    }

    /** The diff's lines, hunks separated by a gap line, cut at `maxLines`. */
    private fun turnFile(path: String, diff: FileDiff, maxLines: Int): TurnFile {
        val lines = ArrayList<TurnDiffLine>()
        var truncated = diff.tooLarge
        diff.hunks.forEachIndexed { index, hunk ->
            if (index > 0) lines.add(TurnDiffLine(DiffKind.GAP, ""))
            for (line in hunk) {
                if (lines.size >= maxLines) {
                    truncated = true
                    break
                }
                lines.add(TurnDiffLine(line.kind, line.text.take(400)))
            }
        }
        return TurnFile(path, diff.added, diff.removed, diff.isNewFile, lines, truncated)
    }
}
