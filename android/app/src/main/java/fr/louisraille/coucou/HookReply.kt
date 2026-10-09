// What goes back to the agent — the only place that knows each agent's reply
// contract. Port of windows/hook/src/reply.rs (the Mac's HookServer.swift).
//
// The one rule that matters: nothing that allows anything is ever sent without
// a decision a human tapped. With no decision the reply is an empty `{}`, or
// the agent's explicit "ask", and every agent then asks in its own terminal
// exactly as if Coucou were not there.

package fr.louisraille.coucou

import org.json.JSONArray
import org.json.JSONObject

object HookReply {
    /** The agents whose permission requests the island can answer. */
    fun takesDecisions(agent: String) = agent == "" || agent == "codex" || agent == "copilot" || agent == "muse"

    /**
     * The body to send for `event` from `agent` ("" is Claude Code), given the
     * island's `decision` — "allow", "deny", `{"answers":{…}}`, or null when
     * nobody tapped — and, for Claude Code's AskUserQuestion, the question as it
     * was asked (`tool_input`).
     */
    fun body(agent: String, event: String, decision: String?, question: JSONObject?): String {
        if (event != "PermissionRequest") {
            // Antigravity reads "{}" on PreToolUse as a denial. "ask" keeps its own prompt.
            if (agent == "antigravity" && event == "PreToolUse") return """{"decision":"ask"}"""
            return "{}"
        }
        val d = decision?.trim()?.takeIf { takesDecisions(agent) }
        return when (agent) {
            // Copilot CLI and Muse Code read a bare permissionDecision. Copilot is
            // fail-closed: it must always get a word, so no decision is an "ask".
            "copilot", "muse" -> when (d) {
                "allow" -> """{"permissionDecision":"allow"}"""
                "deny" -> """{"permissionDecision":"deny"}"""
                else -> if (agent == "copilot") """{"permissionDecision":"ask"}""" else "{}"
            }
            // Claude Code and Codex share the documented hookSpecificOutput. Only
            // Claude Code asks questions.
            "" -> decisionJson(d, question)
            else -> decisionJson(d, null)
        } ?: "{}"
    }

    /**
     * The documented PermissionRequest output, or null: anything not recognised
     * says nothing at all rather than guessing.
     * See https://code.claude.com/docs/en/hooks
     */
    private fun decisionJson(decision: String?, question: JSONObject?): String? {
        decision ?: return null
        val behavior = if (decision.startsWith("{")) {
            // An answer to AskUserQuestion: Claude Code takes its own input back
            // with an `answers` map added. Nothing else of the input can change.
            val answers = try {
                JSONObject(decision).optJSONObject("answers")
            } catch (_: Exception) {
                null
            } ?: return null
            if (question == null || !answersFit(question, answers)) return null
            val input = JSONObject(question.toString()).put("answers", answers)
            JSONObject().put("behavior", "allow").put("updatedInput", input)
        } else when (decision) {
            "allow" -> JSONObject().put("behavior", "allow")
            "deny" -> JSONObject().put("behavior", "deny").put("message", "Denied from Coucou")
            else -> return null
        }
        return JSONObject().put(
            "hookSpecificOutput",
            JSONObject().put("hookEventName", "PermissionRequest").put("decision", behavior),
        ).toString()
    }

    /**
     * True when `answers` answers exactly the questions Claude Code asked: one
     * entry per question, keyed by its text; a single-select answer is one of
     * its option labels, a multi-select answer a non-empty list of distinct
     * labels. Same rule as `QuestionPayload.accepts` on macOS.
     */
    private fun answersFit(question: JSONObject, answers: JSONObject): Boolean {
        val items = question.optJSONArray("questions") ?: return false
        if (items.length() == 0 || items.length() != answers.length()) return false
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: return false
            val text = item.opt("question") as? String ?: return false
            val options = item.optJSONArray("options") ?: JSONArray()
            val labels = (0 until options.length()).mapNotNull { options.optJSONObject(it)?.opt("label") as? String }
            val multi = item.optBoolean("multiSelect", false)
            when (val pick = answers.opt(text)) {
                is String -> if (multi || pick !in labels) return false
                is JSONArray -> {
                    val picks = (0 until pick.length()).map { pick.opt(it) as? String ?: return false }
                    if (!multi || picks.isEmpty() || picks.toSet().size != picks.size || !labels.containsAll(picks)) return false
                }
                else -> return false
            }
        }
        return true
    }
}
