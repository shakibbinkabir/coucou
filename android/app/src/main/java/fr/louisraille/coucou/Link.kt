// What the screens read and what they send back — PhoneLink.swift on the iPhone.
//
// There, the Mac publishes sessions to the user's iCloud and the iPhone fetches
// them. Android has no iCloud: the agents' hooks send their events straight to
// this phone over the local network (HookServer.kt), Hooks.kt turns them into
// sessions, and this object holds the result for the screens, the
// notifications and the widgets. Decisions go back the way the request came.
//
// Everything here is read and written on the main thread.

package fr.louisraille.coucou

import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.util.concurrent.Executors

object Link {
    sealed interface Status {
        /** The link is starting. */
        data object Starting : Status

        /** Listening, and no computer has sent anything yet. */
        data object Waiting : Status

        data object Ready : Status

        /** The link could not start (the port is taken, no network). */
        data class Failed(val message: String) : Status
    }

    var status by mutableStateOf<Status>(Status.Starting)

    /** The first look at what was kept is over: the intro can end. */
    var firstSyncDone by mutableStateOf(false)

    var sessions by mutableStateOf<List<SessionItem>>(emptyList())
        private set

    /** The last turn of each session (prompt, actions, diffs, answer), by pill ID. */
    val turns = mutableStateMapOf<String, TurnSnapshot>()

    /** Turns seen before the latest one, and today's tally (this phone only). */
    var archive by mutableStateOf(TurnArchive())
        private set

    /** Decisions taken on this phone, newest first. */
    var history by mutableStateOf<List<DecisionLog>>(emptyList())
        private set

    /** A session to open, from a notification or a widget. */
    var openPillId by mutableStateOf<String?>(null)

    /** Set when "Review" is tapped on an approval notification. */
    var reviewFingerprint by mutableStateOf<String?>(null)

    var notificationsAllowed by mutableStateOf<Boolean?>(null)

    /** When the last event came in (ms since 1970), and from which address. */
    var lastEventAt by mutableStateOf(0L)
        private set
    var lastEventFrom by mutableStateOf("")
        private set

    /** Called after every change of the sessions (the service: notifications, widgets). */
    var onSessionsChanged: ((before: List<SessionItem>, now: List<SessionItem>) -> Unit)? = null

    // ── From Hooks.kt ───────────────────────────────────────────────────────────

    fun sessionsChanged(now: List<SessionItem>) {
        val before = sessions
        val sorted = now.sortedByDescending { it.updatedAt }
        if (before == sorted) return
        sessions = sorted
        save()
        onSessionsChanged?.invoke(before, sessions)
    }

    fun eventReceived(from: String) {
        lastEventAt = System.currentTimeMillis()
        if (from.isNotEmpty()) lastEventFrom = from
        if (status != Status.Ready) status = Status.Ready
    }

    /** An event of a session pill: the last turn follows (TurnRecorder). */
    fun record(event: String, payload: JSONObject, pillId: String) {
        val before = turns[pillId]
        val turn = TurnRecorder.record(event, payload, pillId, before) ?: return
        archive = archive.update(before, turn)
        turns[pillId] = turn
        save()
    }

    // ── From the screens ────────────────────────────────────────────────────────

    /** The pull to refresh: the link is local, so there is only its state to look at again. */
    suspend fun refresh() {
        LinkService.start(App.instance)
        delay(400)
        status = when {
            HookServer.error != null -> Status.Failed(HookServer.error ?: "")
            lastEventAt > 0 || sessions.isNotEmpty() -> Status.Ready
            else -> Status.Waiting
        }
        firstSyncDone = true
    }

    /**
     * Sends allow / deny for one exact request. It is applied only if the
     * fingerprint still matches the request the agent is waiting on.
     */
    fun decide(decision: Decision, fingerprint: String, pillId: String, summary: String): Boolean {
        if (!Hooks.decide(decision, fingerprint)) return false
        history = (listOf(DecisionLog(decision, pillId, summary, System.currentTimeMillis())) + history).take(50)
        save()
        return true
    }

    /** Allow from a notification, without opening the app (the phone was unlocked by its owner first). */
    fun allowFromOutside(fingerprint: String, pillId: String, from: String): Boolean {
        val command = sessions.firstOrNull { it.approvalFingerprint == fingerprint }?.approvalCommand ?: ""
        return decide(Decision.ALLOW, fingerprint, pillId, command.take(200).ifEmpty { "Allowed from $from" })
    }

    /**
     * Answers the question a session waits on, only if it is still the same
     * question and the picks are among its choices.
     */
    fun answer(fingerprint: String, pillId: String, selections: List<List<String>>): Boolean =
        Hooks.answer(fingerprint, selections)

    /**
     * Sends an instruction to continue a session. Nothing on the computer takes
     * instructions over this link yet, so no session accepts them
     * (`acceptsInstructions` is false) and this always says no.
     */
    @Suppress("UNUSED_PARAMETER")
    fun sendInstruction(text: String, pillId: String): Boolean = false

    // ── The address to give the computer ────────────────────────────────────────

    /** This phone's address on the Wi-Fi, or null when it has none. */
    fun localAddress(context: Context = App.instance): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val props = cm.getLinkProperties(cm.activeNetwork ?: return null) ?: return null
        return props.linkAddresses.map { it.address }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }

    // ── Kept on this phone ──────────────────────────────────────────────────────

    private lateinit var dir: File
    private val disk = Executors.newSingleThreadExecutor()
    private var saveScheduled = false

    /** Reads what was kept: sessions, last turns, past turns, decisions. */
    fun load(context: Context) {
        dir = context.filesDir
        fun read(name: String): JSONObject? = try {
            JSONObject(File(dir, name).readText())
        } catch (_: Exception) {
            null
        }
        // Only the main pill outlives its session, at rest, as on the Mac: any
        // other agent's pill comes back with its next event.
        read("sessions.json")?.optJSONArray("sessions")?.objects()?.map(SessionItem::fromJson)
            ?.filter { it.id == PillCatalog.MAIN }?.let {
                Hooks.restore(it)
                sessions = it
            }
        read("turns.json")?.let { o -> for (id in o.keys()) o.optJSONObject(id)?.let { turns[id] = TurnSnapshot.fromJson(it) } }
        read("turn-archive.json")?.let { archive = TurnArchive.fromJson(it) }
        read("decisions.json")?.optJSONArray("history")?.objects()?.map(DecisionLog::fromJson)?.let { history = it }
    }

    /** Writes it all a moment later, once, however many changes came in the meantime. */
    private fun save() {
        if (saveScheduled || !::dir.isInitialized) return
        saveScheduled = true
        mainHandler.postDelayed({
            saveScheduled = false
            // Snapshots taken here, on the main thread; only the writing leaves it.
            val files = mapOf(
                "sessions.json" to JSONObject().put("sessions", JSONArray(sessions.map { it.toJson() })),
                "turns.json" to JSONObject().apply { for ((id, turn) in turns) put(id, turn.toJson()) },
                "turn-archive.json" to archive.toJson(),
                "decisions.json" to JSONObject().put("history", JSONArray(history.map { it.toJson() })),
            ).mapValues { it.value.toString() }
            disk.execute {
                for ((name, text) in files) {
                    try {
                        val tmp = File(dir, "$name.tmp")
                        tmp.writeText(text)
                        tmp.renameTo(File(dir, name))
                    } catch (_: Exception) {
                        // Losing the kept copy only costs the history after a restart.
                    }
                }
            }
        }, 800)
    }
}
