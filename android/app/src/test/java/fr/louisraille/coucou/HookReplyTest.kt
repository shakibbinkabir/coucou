package fr.louisraille.coucou

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What goes back to an agent. Same cases as windows/hook/src/reply.rs. */
class HookReplyTest {
    private val agents = listOf("", "gemini", "antigravity", "cursor", "codex", "copilot", "muse", "opencode", "amp", "hermes", "claude-desktop", "my-tool")
    private val events = listOf(
        "SessionStart", "UserPromptSubmit", "PreToolUse", "PostToolUse", "PostToolUseFailure", "PermissionRequest",
        "Notification", "Stop", "StopFailure", "SessionEnd", "Interrupt", "SubagentStart", "SubagentStop", "",
    )
    private val question = JSONObject(
        """{"questions":[{"question":"Which one?","header":"Pick","multiSelect":false,
           "options":[{"label":"A","description":""},{"label":"B","description":""}]}]}""",
    )

    /** Whatever a reply says, does it let anything through? */
    private fun allows(out: String): Boolean {
        val v = JSONObject(out)
        return v.optString("permissionDecision") == "allow" ||
            v.optJSONObject("hookSpecificOutput")?.optJSONObject("decision")?.optString("behavior") == "allow" ||
            (v.has("decision") && v.optString("decision") != "ask")
    }

    @Test
    fun nothingIsEverAllowedWithoutADecision() {
        for (agent in agents) for (event in events) {
            // A decline, a timeout or garbage is no decision either.
            for (notADecision in listOf(null, "", "ask", "decline", "maybe", """{"permissionDecision":"allow"}""", """{"answers":{"q":"a"}}""")) {
                val out = HookReply.body(agent, event, notADecision, null)
                assertFalse("$agent $event $notADecision printed $out", allows(out))
            }
        }
    }

    @Test
    fun aDecisionOnlyCountsOnAPermissionRequestFromAnAgentThatTakesOne() {
        for (agent in agents) {
            assertEquals(agent, HookReply.takesDecisions(agent), allows(HookReply.body(agent, "PermissionRequest", "allow", null)))
            for (event in events - "PermissionRequest") assertFalse(allows(HookReply.body(agent, event, "allow", null)))
        }
    }

    @Test
    fun eachAgentGetsItsOwnShape() {
        val allow = """{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"allow"}}}"""
        assertEquals(JSONObject(allow).toString(), HookReply.body("", "PermissionRequest", "allow", null))
        assertEquals(JSONObject(allow).toString(), HookReply.body("codex", "PermissionRequest", "allow", null))
        val deny = JSONObject(HookReply.body("", "PermissionRequest", "deny", null)).getJSONObject("hookSpecificOutput").getJSONObject("decision")
        assertEquals("deny", deny.getString("behavior"))
        assertEquals("Denied from Coucou", deny.getString("message"))
        assertEquals("""{"permissionDecision":"allow"}""", HookReply.body("copilot", "PermissionRequest", "allow", null))
        assertEquals("""{"permissionDecision":"deny"}""", HookReply.body("muse", "PermissionRequest", "deny", null))
        // Copilot is fail-closed: no decision is an explicit "ask", never silence.
        assertEquals("""{"permissionDecision":"ask"}""", HookReply.body("copilot", "PermissionRequest", null, null))
        // Antigravity reads "{}" on PreToolUse as a denial.
        assertEquals("""{"decision":"ask"}""", HookReply.body("antigravity", "PreToolUse", null, null))
        assertEquals("{}", HookReply.body("", "PreToolUse", null, null))
    }

    @Test
    fun anAnswerGoesBackAsTheQuestionWithItsAnswers() {
        val out = JSONObject(HookReply.body("", "PermissionRequest", """{"answers":{"Which one?":"B"}}""", question))
        val decision = out.getJSONObject("hookSpecificOutput").getJSONObject("decision")
        assertEquals("allow", decision.getString("behavior"))
        assertEquals("B", decision.getJSONObject("updatedInput").getJSONObject("answers").getString("Which one?"))
        assertEquals(question.getJSONArray("questions").toString(), decision.getJSONObject("updatedInput").getJSONArray("questions").toString())
    }

    @Test
    fun anAnswerThatDoesNotFitTheQuestionSaysNothing() {
        for (answers in listOf(
            """{"answers":{"Which one?":"C"}}""", // not one of the choices
            """{"answers":{"Another?":"A"}}""", // not the question asked
            """{"answers":{"Which one?":["A"]}}""", // a list for a single choice
            """{"answers":{}}""",
        )) {
            assertEquals(answers, "{}", HookReply.body("", "PermissionRequest", answers, question))
        }
        // Only Claude Code asks questions; and an answer needs its question.
        assertEquals("{}", HookReply.body("codex", "PermissionRequest", """{"answers":{"Which one?":"B"}}""", question))
        assertTrue(HookReply.body("", "PermissionRequest", """{"answers":{"Which one?":"B"}}""", null) == "{}")
    }
}
