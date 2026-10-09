// Hook events from Claude Code and every other agent → sessions.
//
// On the Mac this is HookServer.processEvent (the island's tasks), then
// SessionPublisher and TurnRecorder, which send the result to the iPhone. Here
// the events come straight to the phone (HookServer.kt), so the same three
// steps run on it: the tasks below, their snapshot for the screens (Link.kt),
// and the last turn (Recorder.kt). Same rules as windows/src/island/hooks.ts.
//
// Main thread only.

package fr.louisraille.coucou

import org.json.JSONArray
import org.json.JSONObject

object Hooks {
    /** A session pill, as the Mac's AgentTask. */
    private class Task(val id: String, var name: String, val color: String) {
        var state = BotState.IDLE
        val steps = ArrayList<String>()
        var finalLine = ""
        var cwd = ""
        var sessionId = ""
        var host = ""
        var updatedAt = System.currentTimeMillis()
    }

    /** The one request waiting for an answer (a permission, or a question). */
    class Pending(
        val requestId: String, val pillId: String, val sessionId: String,
        val tool: String, val command: String, val question: QuestionPayload?,
    )

    private val tasks = LinkedHashMap<String, Task>()

    var pending: Pending? = null
        private set

    private const val CURSOR_ID = "agent_cursor"

    /** The return to idle that Stop arms, per pill, so the next turn can cancel it. */
    private val stopTimers = HashMap<String, Runnable>()

    /** Events after which a pending permission request of the same session is moot. */
    private val TURN_OVER = setOf("Stop", "StopFailure", "UserPromptSubmit", "SessionEnd", "Interrupt")

    /** localizedStep() — same labels as the macOS app. */
    private val TOOL_LABELS = mapOf(
        "Bash" to "Runs", "Read" to "Reads", "Write" to "Writes", "Edit" to "Edits",
        "Glob" to "Searches", "Grep" to "Searches", "WebSearch" to "Searches the web",
        "WebFetch" to "Fetches", "TodoWrite" to "Tasks", "Task" to "Agent", "LS" to "Lists",
        "MultiEdit" to "Edits", "NotebookEdit" to "Notebook", "PowerShell" to "Runs",
        // Antigravity's tools (#298).
        "run_command" to "Runs", "view_file" to "Reads", "write_to_file" to "Writes",
        "replace_file_content" to "Edits", "read_url_content" to "Fetches", "search_web" to "Searches the web",
    )

    /**
     * What Allow actually authorises, most specific field first: approving
     * "Write" tells you nothing, approving the path everything.
     */
    private val APPROVAL_FIELDS = listOf("command", "file_path", "path", "url", "query", "pattern", "prompt")

    private fun lastPathComponent(p: String): String = p.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')

    private fun stepLabel(tool: String, input: JSONObject): String {
        val label = TOOL_LABELS[tool] ?: tool
        input.str("command").takeIf { it.isNotEmpty() }?.let { return "$label · ${it.take(40)}" }
        input.str("path").takeIf { it.isNotEmpty() }?.let { return "$label · ${lastPathComponent(it)}" }
        input.str("file_path").takeIf { it.isNotEmpty() }?.let { return "$label · ${lastPathComponent(it)}" }
        input.str("query").takeIf { it.isNotEmpty() }?.let { return "$label · ${it.take(40)}" }
        return label
    }

    private fun approvalTarget(tool: String, input: JSONObject): String {
        for (field in APPROVAL_FIELDS) {
            val value = (input.opt(field) as? String)?.trim()
            if (!value.isNullOrEmpty()) return value
        }
        return tool
    }

    private fun cancelStopTimer(id: String) {
        cancel(stopTimers.remove(id))
    }

    private fun touch(id: String, state: BotState? = null, step: String? = null) {
        val t = tasks[id] ?: return
        if (state != null) t.state = state
        if (step != null) {
            t.steps.add(step)
            if (t.steps.size > 20) t.steps.removeAt(0)
        }
        t.updatedAt = System.currentTimeMillis()
    }

    /** The session is over: the main pill goes back as it was, any other goes away. */
    private fun endSession(id: String) {
        val t = tasks[id] ?: return
        if (id == PillCatalog.MAIN) {
            t.state = BotState.IDLE
            t.steps.clear()
            t.finalLine = ""
            t.name = ""
            t.updatedAt = System.currentTimeMillis()
        } else {
            tasks.remove(id)
        }
    }

    // ── What the screens get ────────────────────────────────────────────────────

    /** The sessions as they are now (SessionSnapshot.all on the Mac). */
    private fun publish() {
        val req = pending
        Link.sessionsChanged(tasks.values.map { t ->
            val mine = req?.pillId == t.id
            val question = if (mine) req?.question else null
            SessionItem(
                id = t.id, name = t.name, color = t.color, state = t.state,
                stepIndex = (t.steps.size - 1).coerceAtLeast(0), steps = t.steps.toList(),
                needsApproval = mine && question == null,
                approvalCommand = if (mine && question == null) req!!.command else "",
                approvalFingerprint = if (mine && question == null) req!!.requestId else "",
                // The phone has nobody on the computer to hand an instruction to.
                acceptsInstructions = false,
                question = question?.items?.joinToString("\n") { it.question } ?: "",
                questionPayload = question,
                questionFingerprint = question?.fingerprint ?: "",
                finalLine = t.finalLine, cwd = t.cwd, updatedAt = t.updatedAt, macName = HostNames.name(t.host),
            )
        })
    }

    /** Sessions read back at launch (Link.load): they are shown until news of them comes. */
    fun restore(sessions: List<SessionItem>) {
        for (s in sessions) {
            if (tasks.containsKey(s.id)) continue
            tasks[s.id] = Task(s.id, s.name, s.color).apply {
                steps.addAll(s.steps)
                finalLine = s.finalLine
                cwd = s.cwd
                updatedAt = s.updatedAt
            }
        }
    }

    /** A computer's name was found: the sessions show it. */
    fun hostNamed() = publish()

    // ── Answers ─────────────────────────────────────────────────────────────────

    /** The request has its answer, or is withdrawn: the session carries on. */
    private fun dropPending() {
        val req = pending ?: return
        pending = null
        touch(req.pillId, BotState.WORKING)
        publish()
    }

    /** The link gave up on a request (timeout, or the terminal answered first). */
    fun withdraw(requestId: String) {
        if (pending?.requestId == requestId) dropPending()
    }

    /**
     * Allow or deny, for one exact request: it is applied only if the
     * fingerprint is still the one of the request that waits.
     */
    fun decide(decision: Decision, fingerprint: String): Boolean {
        val req = pending ?: return false
        if (req.requestId != fingerprint || req.question != null) return false
        HookServer.decide(req.requestId, if (decision == Decision.ALLOW) "allow" else "deny")
        dropPending()
        return true
    }

    /**
     * Answers the question that waits: one list of labels per question. Taken
     * only if it is still the same question and the picks are among its choices.
     */
    fun answer(fingerprint: String, selections: List<List<String>>): Boolean {
        val req = pending ?: return false
        val question = req.question ?: return false
        if (question.fingerprint != fingerprint || !question.accepts(selections)) return false
        val answers = JSONObject()
        question.items.zip(selections).forEach { (item, picks) ->
            answers.put(item.question, if (item.multiSelect) JSONArray(picks) else picks.first())
        }
        HookServer.decide(req.requestId, JSONObject().put("answers", answers).toString())
        dropPending()
        return true
    }

    // ── Events ──────────────────────────────────────────────────────────────────

    fun handle(payload: JSONObject, from: String = "") {
        val name = payload.str("hook_event_name")
        val cwd = payload.str("cwd")
        val projectName = lastPathComponent(cwd)

        // Route to the right pill. Valid coucou_agent → "agent_<name>" pill.
        // Absent or invalid → Claude Code's own pill: Cursor's when it runs in
        // Cursor's terminal (Mac #120), the main one otherwise.
        val validAgent = validateAgent(payload.str("coucou_agent"))
        val workspaceId = if (payload.str("term_editor") == "cursor") CURSOR_ID else PillCatalog.MAIN
        val agentId = if (validAgent != null) "agent_$validAgent" else workspaceId
        val isExternalAgent = validAgent != null
        val sessionId = payload.str("session_id")
        val requestId = payload.str("request_id")

        /** The session's pill, named after its project for the session. */
        fun ensurePill() {
            val t = tasks.getOrPut(agentId) { Task(agentId, "", PillCatalog.colorFor(agentId)) }
            if (projectName.isNotEmpty()) t.name = projectName
            if (cwd.isNotEmpty()) t.cwd = cwd
            if (sessionId.isNotEmpty()) t.sessionId = sessionId
            if (from.isNotEmpty()) t.host = from
        }

        fun clearFinalLine() {
            tasks[agentId]?.finalLine = ""
        }

        // The turn that asked for a permission is over — answered in the terminal,
        // interrupted, or a new prompt — so the card would be lying. It goes, and
        // the request is released without a decision. Same rule as the Mac.
        val req = pending
        if (req != null && name in TURN_OVER && req.pillId == agentId && req.sessionId == sessionId) {
            HookServer.decide(req.requestId, "decline")
            dropPending()
        }

        when (name) {
            "SessionStart" -> {
                ensurePill()
                clearFinalLine()
                touch(agentId)
            }

            "UserPromptSubmit" -> {
                ensurePill()
                cancelStopTimer(agentId)
                clearFinalLine()
                // The field is `prompt`; `message` belongs to Notification and Stop.
                val asked = payload.str("prompt").ifEmpty { payload.str("message") }
                touch(agentId, BotState.THINKING, asked.take(60).ifEmpty { null })
            }

            "PreToolUse" -> {
                ensurePill()
                cancelStopTimer(agentId)
                clearFinalLine()
                val tool = payload.str("tool_name").ifEmpty { "Tool" }
                touch(agentId, BotState.WORKING, stepLabel(tool, payload.optJSONObject("tool_input") ?: JSONObject()))
            }

            "PostToolUse" -> {
                cancelStopTimer(agentId)
                // The question was answered in the terminal: the card would be lying.
                val waiting = pending
                if (payload.str("tool_name") == "AskUserQuestion" && waiting?.question != null && waiting.sessionId == sessionId) {
                    HookServer.decide(waiting.requestId, "decline")
                    dropPending()
                }
                touch(agentId, BotState.WORKING)
            }

            "PostToolUseFailure" -> {
                cancelStopTimer(agentId)
                touch(agentId, BotState.WORKING, "⚠ failed")
            }

            "Notification" -> {
                val message = payload.str("message")
                val lower = message.lowercase()
                if (lower.contains("rate limit") || lower.contains("limite d")) {
                    cancelStopTimer(agentId)
                    touch(agentId, BotState.RATELIMIT)
                } else if (message.endsWith("?")) {
                    cancelStopTimer(agentId)
                    touch(agentId, BotState.QUESTION, message)
                }
            }

            "Stop" -> {
                ensurePill()
                // Claude Code puts the turn's answer in the Stop payload itself.
                // Other agents report their last words the same way, or as `message`.
                val finalText = DiffEngine.toOneLine(payload.str("last_assistant_message").ifEmpty { payload.str("message") })
                touch(agentId, BotState.FINISHED, finalText.ifEmpty { null })
                tasks[agentId]?.finalLine = finalText
                cancelStopTimer(agentId)
                stopTimers[agentId] = after(5200) {
                    stopTimers.remove(agentId)
                    if (isExternalAgent) tasks.remove(agentId) else touch(agentId, BotState.IDLE)
                    publish()
                }
            }

            "Interrupt" -> {
                // Codex: the user stopped the turn. Back to idle, nothing to celebrate.
                cancelStopTimer(agentId)
                touch(agentId, BotState.IDLE)
            }

            "StopFailure" -> {
                ensurePill()
                cancelStopTimer(agentId)
                touch(agentId, BotState.ERROR)
                // Claude Code says what went wrong in `error` ("API Error: Rate limit reached").
                tasks[agentId]?.finalLine = payload.str("error").ifEmpty { payload.str("message") }.take(200)
            }

            "SessionEnd" -> {
                // Nothing left for the timer to do, and it must not outlive the
                // session: a pill recreated within 5.2 s would be removed by it.
                cancelStopTimer(agentId)
                endSession(agentId)
            }

            "SubagentStart" -> touch(agentId, step = "+ subagent")
            "SubagentStop" -> touch(agentId, step = "• subagent done")

            "PermissionRequest" -> {
                // One request at a time. A second one must never quietly replace
                // the first — that would leave a human looking at request B while
                // request A waits for a decision nobody can give. It goes straight
                // back to the terminal.
                if (pending != null && pending?.requestId != requestId) {
                    HookServer.decide(requestId, "decline")
                    return
                }
                ensurePill()
                cancelStopTimer(agentId)
                val tool = payload.str("tool_name").ifEmpty { "Tool" }
                val input = payload.optJSONObject("tool_input") ?: JSONObject()
                // Claude Code asking a question is not a permission to grant: the
                // choices are shown and the one picked is sent back. A question that
                // cannot be shown whole is left to the terminal.
                val question = if (tool == "AskUserQuestion" && !isExternalAgent) QuestionPayload.fromToolInput(input) else null
                if (tool == "AskUserQuestion" && question == null) {
                    HookServer.decide(requestId, "decline")
                    return
                }
                pending = Pending(requestId, agentId, sessionId, tool, approvalTarget(tool, input), question)
                touch(agentId, if (question != null) BotState.QUESTION else BotState.APPROVAL)
            }
        }

        if (PillCatalog.isSession(agentId) && tasks.containsKey(agentId)) Link.record(name, payload, agentId)
        Link.eventReceived(from)
        publish()
    }
}

/**
 * The names of the computers that send sessions, found once per address in the
 * background. Until a name is known, and when the network has none to give, a
 * computer is called by its address.
 */
object HostNames {
    private val names = HashMap<String, String>()
    private val asked = HashSet<String>()

    fun name(address: String): String {
        if (address.isEmpty()) return ""
        names[address]?.let { return it }
        if (asked.add(address)) {
            Thread {
                val found = try {
                    java.net.InetAddress.getByName(address).canonicalHostName
                } catch (_: Exception) {
                    address
                }
                val short = if (found == address) address else found.substringBefore('.')
                mainHandler.post {
                    names[address] = short
                    if (short != address) Hooks.hostNamed()
                }
            }.start()
        }
        return address
    }
}
