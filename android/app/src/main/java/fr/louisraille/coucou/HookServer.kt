// The link: a small HTTP server on the local network that takes the place of
// the Mac's Unix socket and of Windows' named pipe.
//
// An agent's hook POSTs its event here as JSON (`POST /hook`, the hook's own
// payload, untouched) — with curl, or with Claude Code's `http` hooks. Every
// event is answered at once with `{}`, except `PermissionRequest`: that one
// keeps its connection open until a human taps Allow or Deny on the phone, and
// the answer goes back on it. That is how approving from the phone works.
//
// The agent is never blocked by us:
//   * if the phone is away the hook's own connect timeout gives up, and the
//     agent carries on exactly as if Coucou were not installed;
//   * a request is dropped as soon as the other end hangs up (the question was
//     answered in the terminal), and after 108 s whatever happens.
//
// Whoever holds the token may send sessions here. Nothing a sender says can
// allow anything: only a tap on this phone does (HookReply.kt).

package fr.louisraille.coucou

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

object HookServer {
    /** Slightly under the 110 s the desktop relays wait, and the hook timeout we suggest. */
    private const val DECISION_TIMEOUT_MS = 108_000L
    private const val MAX_HEAD = 16 * 1024
    private const val MAX_BODY = 1 shl 20

    private var server: ServerSocket? = null
    private val workers = Executors.newCachedThreadPool()

    /** Permission requests waiting for a human, by request id. */
    private val pending = ConcurrentHashMap<String, LinkedBlockingQueue<String>>()

    /** Why the server is not listening, for Settings; null when it is. */
    @Volatile
    var error: String? = "Not started"
        private set

    val running: Boolean get() = server?.isClosed == false

    fun start(port: Int) {
        if (running) return
        try {
            val s = ServerSocket()
            s.reuseAddress = true
            s.bind(InetSocketAddress(port))
            server = s
            error = null
            Thread({ acceptLoop(s) }, "coucou-link").start()
        } catch (e: Exception) {
            error = e.message ?: "Could not listen on port $port"
        }
    }

    fun stop() {
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
        error = "Not started"
    }

    /** A human answered: "allow", "deny", `{"answers":{…}}` — or "decline" to hand it back. */
    fun decide(requestId: String, decision: String) {
        pending[requestId]?.offer(decision)
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val socket = try {
                s.accept()
            } catch (_: Exception) {
                break
            }
            workers.execute {
                try {
                    socket.use { serve(it) }
                } catch (_: Exception) {
                    // A broken connection is the sender's business, never a crash here.
                }
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.soTimeout = 5_000
        val input = BufferedInputStream(socket.getInputStream())
        val out = socket.getOutputStream()

        fun respond(status: String, body: String) {
            val bytes = body.toByteArray()
            out.write(
                ("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n").toByteArray(),
            )
            out.write(bytes)
            out.flush()
        }

        val head = readHead(input) ?: return
        val lines = head.split("\r\n")
        val request = lines[0].split(" ")
        if (request.size < 2) return respond("400 Bad Request", "{}")
        val method = request[0]
        val target = request[1]
        val path = target.substringBefore('?')
        val query = target.substringAfter('?', "").split('&').filter { it.contains('=') }.associate {
            it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8")
        }
        val headers = lines.drop(1).filter { it.contains(':') }.associate {
            it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim()
        }

        val given = headers["authorization"]?.removePrefix("Bearer ")?.trim() ?: query["token"] ?: ""
        if (!MessageDigest.isEqual(given.toByteArray(), Prefs.linkToken().toByteArray())) {
            return respond("401 Unauthorized", """{"error":"wrong token"}""")
        }
        if (method == "GET" && path == "/ping") return respond("200 OK", """{"app":"coucou"}""")
        if (method != "POST" || path != "/hook") return respond("404 Not Found", "{}")

        val length = headers["content-length"]?.toIntOrNull() ?: return respond("411 Length Required", "{}")
        if (length < 0 || length > MAX_BODY) return respond("413 Payload Too Large", "{}")
        // curl waits a second for this before sending a body over 1 KB.
        if (headers["expect"]?.lowercase() == "100-continue") {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
            out.flush()
        }
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) return
            read += n
        }
        // Some shells hand curl a UTF-8 BOM; the JSON parser would choke on it.
        val payload = try {
            JSONObject(String(body).removePrefix("﻿"))
        } catch (_: Exception) {
            return respond("400 Bad Request", "{}")
        }

        // Which agent this hook was installed for (`?agent=codex`), so the event
        // goes to the right pill. Absent means Claude Code.
        val agent = validateAgent(query["agent"] ?: payload.str("coucou_agent")) ?: ""
        if (agent.isNotEmpty()) payload.put("coucou_agent", agent) else payload.remove("coucou_agent")
        val event = payload.str("hook_event_name").ifEmpty { query["event"] ?: "" }
        payload.put("hook_event_name", event)
        val from = socket.inetAddress?.hostAddress ?: ""

        if (event != "PermissionRequest" || !HookReply.takesDecisions(agent)) {
            mainHandler.post { Hooks.handle(payload, from) }
            return respond("200 OK", HookReply.body(agent, event, null, null))
        }

        // Kept whole: what goes back to Claude Code must be its own input.
        val question = if (payload.str("tool_name") == "AskUserQuestion") payload.optJSONObject("tool_input") else null
        val id = UUID.randomUUID().toString()
        payload.put("request_id", id)
        val answer = LinkedBlockingQueue<String>()
        pending[id] = answer
        mainHandler.post { Hooks.handle(payload, from) }

        var decision: String? = null
        val deadline = nowMs() + DECISION_TIMEOUT_MS
        socket.soTimeout = 1
        while (nowMs() < deadline) {
            decision = answer.poll(400, TimeUnit.MILLISECONDS)
            if (decision != null || hungUp(input)) break
        }
        pending.remove(id)
        if (decision == null || decision == "decline") {
            // Nobody answered here: the card would be lying from now on.
            mainHandler.post { Hooks.withdraw(id) }
            decision = null
        }
        respond("200 OK", HookReply.body(agent, event, decision, question))
    }

    /** True when the other end closed the connection (the terminal answered first). */
    private fun hungUp(input: InputStream): Boolean = try {
        input.read() < 0
    } catch (_: SocketTimeoutException) {
        false
    } catch (_: Exception) {
        true
    }

    /** The request line and headers, up to the blank line; null if there is none in 16 KB. */
    private fun readHead(input: InputStream): String? {
        val buf = StringBuilder()
        while (buf.length < MAX_HEAD) {
            val b = input.read()
            if (b < 0) return null
            buf.append(b.toChar())
            if (buf.endsWith("\r\n\r\n")) return buf.substring(0, buf.length - 4)
        }
        return null
    }
}
