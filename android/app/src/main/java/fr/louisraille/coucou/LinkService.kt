// What runs when the app is not on screen: the link, and the notifications.
//
// On the iPhone, iCloud pushes wake the app and a Live Activity shows Mochi on
// the Lock Screen. Here a foreground service keeps the link listening, and its
// own notification is that Live Activity: Mochi with the most urgent session's
// state, and Allow / Deny when a command waits for your OK. The rest is
// AgentNotifier.swift: a notification for each permission request, question,
// finished or failed turn, with the same actions.

package fr.louisraille.coucou

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.compose.ui.graphics.toArgb

class LinkService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
        HookServer.start(Prefs.port)
        Link.status = when (val error = HookServer.error) {
            null -> if (Link.lastEventAt > 0 || Link.sessions.isNotEmpty()) Link.Status.Ready else Link.Status.Waiting
            else -> Link.Status.Failed(friendly(error))
        }
        Link.firstSyncDone = true
        Link.onSessionsChanged = { before, now -> Notifier.sync(this, before, now) }
        // A build run from a computer (adb) says how to reach it, for testing the link.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) Log.d("Coucou", LinkSetup.testCommand())
        val live = Notifier.live(this, Link.sessions)
        if (Build.VERSION.SDK_INT >= 34) startForeground(Notifier.LIVE_ID, live, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(Notifier.LIVE_ID, live)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Asked again once notifications are allowed: Mochi's notification shows from then on.
        Notifier.sync(this, Link.sessions, Link.sessions)
        return START_STICKY
    }

    override fun onDestroy() {
        Link.onSessionsChanged = null
        HookServer.stop()
        super.onDestroy()
    }

    /** Why the link is down, in words. */
    private fun friendly(error: String): String =
        if (error.contains("in use", ignoreCase = true)) "Port ${Prefs.port} is already used by another app on this phone."
        else "The link could not start: $error"

    companion object {
        /** Starts the link if it is not running. Only works while the app is on screen, or at boot. */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, LinkService::class.java))
            } catch (_: Exception) {
                // Not allowed from the background: the link starts when the app is next opened.
            }
        }
    }
}

/** The phone restarted: the link comes back without the app being opened. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs.onboardingDone) LinkService.start(context)
    }
}

/** Allow, Deny or a choice tapped on a notification. */
class DecisionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val fingerprint = intent.getStringExtra(FINGERPRINT) ?: return
        val pillId = intent.getStringExtra(PILL) ?: ""
        when (intent.action) {
            // Answered right there: Android had the phone unlocked by its owner first.
            ALLOW -> if (Link.allowFromOutside(fingerprint, pillId, "a notification")) Haptics.success() else Haptics.error()
            DENY -> {
                val command = Link.sessions.firstOrNull { it.approvalFingerprint == fingerprint }?.approvalCommand
                Link.decide(Decision.DENY, fingerprint, pillId, command?.take(200) ?: "Denied from the notification")
                Haptics.impact()
            }
            PICK -> intent.getStringExtra(LABEL)?.let { Link.answer(fingerprint, pillId, listOf(listOf(it))) }
        }
        // Answered or too late, the notification has nothing left to ask.
        Notifier.sync(context, Link.sessions, Link.sessions)
    }

    companion object {
        const val ALLOW = "fr.louisraille.coucou.ALLOW"
        const val DENY = "fr.louisraille.coucou.DENY"
        const val PICK = "fr.louisraille.coucou.PICK"
        const val FINGERPRINT = "fingerprint"
        const val PILL = "pillId"
        const val LABEL = "label"
    }
}

object Notifier {
    const val LIVE_ID = 1
    private const val APPROVAL_ID = 2
    private const val QUESTION_ID = 3
    private const val DONE_BASE = 1000

    private const val LIVE = "live"
    private const val QUIET = "quiet"

    /** Channels that ring: (id, name, sound, importance). One set with Mochi's sounds, one with the phone's. */
    private val RINGING = listOf(
        Triple("approval", "Permission requests", NotificationManager.IMPORTANCE_HIGH),
        Triple("question", "Questions", NotificationManager.IMPORTANCE_HIGH),
        Triple("finish", "Finished turns", NotificationManager.IMPORTANCE_DEFAULT),
        Triple("error", "Errors", NotificationManager.IMPORTANCE_DEFAULT),
    )

    /** Requests already notified once, so a redraw of the list does not ring again. */
    private val notified = HashSet<String>()

    /** What each session looked like the last time (AgentNotifier.lastSeen). */
    private val lastSeen = HashMap<String, String>()

    private fun manager(context: Context) = context.getSystemService(NotificationManager::class.java)

    private fun channel(sound: String) = "$sound-${if (Prefs.mochiSounds) "mochi" else "default"}"

    /** A channel keeps the sound it was made with: one set per choice, the other one is deleted. */
    fun createChannels(context: Context) {
        val nm = manager(context)
        nm.createNotificationChannel(
            NotificationChannel(LIVE, "Mochi", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Your agents' state while the link is on"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(QUIET, "Quiet hours", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Finished turns and errors during your quiet hours"
            },
        )
        val mochi = Prefs.mochiSounds
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build()
        for ((sound, name, importance) in RINGING) {
            nm.deleteNotificationChannel("$sound-${if (mochi) "default" else "mochi"}")
            nm.createNotificationChannel(
                NotificationChannel(channel(sound), name, importance).apply {
                    // One of Mochi's sounds (the Mac app's own WAV), or the phone's default.
                    if (mochi) {
                        // By name: a resource's number changes from one build to the next.
                        setSound(Uri.parse("android.resource://${context.packageName}/raw/$sound"), attributes)
                    }
                },
            )
        }
    }

    /** The sounds setting changed: the channels are made again with the new sound. */
    fun soundsChanged(context: Context) = createChannels(context)

    /** The app came to the screen: what it shows needs no banner. */
    fun appOpened(context: Context) {
        val nm = manager(context)
        nm.cancel(APPROVAL_ID)
        nm.cancel(QUESTION_ID)
    }

    // ── Intents ─────────────────────────────────────────────────────────────────

    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun open(context: Context, pillId: String?, review: String? = null): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(MainActivity.EXTRA_PILL, pillId)
            .putExtra(MainActivity.EXTRA_REVIEW, review)
        return PendingIntent.getActivity(context, (pillId + review).hashCode(), intent, FLAGS)
    }

    /** An action that answers from the notification. Android unlocks the phone first. */
    private fun action(context: Context, title: String, action: String, fingerprint: String, pillId: String, label: String? = null): Notification.Action {
        val intent = Intent(context, DecisionReceiver::class.java).setAction(action)
            .putExtra(DecisionReceiver.FINGERPRINT, fingerprint).putExtra(DecisionReceiver.PILL, pillId).putExtra(DecisionReceiver.LABEL, label)
        val pending = PendingIntent.getBroadcast(context, (action + fingerprint + label).hashCode(), intent, FLAGS)
        val builder = Notification.Action.Builder(null as Icon?, title, pending)
        if (Build.VERSION.SDK_INT >= 31) builder.setAuthenticationRequired(true)
        return builder.build()
    }

    /** White Mochi on the agent's colour, as in the list. */
    private fun tile(session: SessionItem?): Bitmap {
        val size = 192
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = mochiTile(session?.color ?: "#F5F6F8").toArgb() }
        canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), size * 0.27f, size * 0.27f, paint)
        val mochi = mochiBitmap((size * 0.8f).toInt(), session?.state ?: BotState.SLEEPING, showBadge = session != null)
        canvas.drawBitmap(mochi, size * 0.1f, size * 0.1f, null)
        return bitmap
    }

    private fun base(context: Context, channel: String, session: SessionItem?) =
        Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_mochi)
            .setLargeIcon(tile(session))
            .setColor(Ios.accent.toArgb())
            .setContentIntent(open(context, session?.id))
            .setShowWhen(false)

    // ── The live one ────────────────────────────────────────────────────────────

    /**
     * Mochi while the link is on: the most urgent session and its state, with
     * Allow and Deny when a command waits. The Lock Screen gets the agent and its
     * state only, never a project name or a command.
     */
    fun live(context: Context, sessions: List<SessionItem>): Notification {
        val lead = sessions.sortedByUrgency().firstOrNull()
        val headline = sessions.summary ?: if (sessions.isEmpty()) "Nothing yet" else "All quiet"
        val builder = base(context, LIVE, lead)
            .setContentTitle(headline)
            .setContentText(lead?.let { "${it.title} · ${it.statusText}" } ?: "Mochi is watching for your agents")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(
                Notification.Builder(context, LIVE).setSmallIcon(R.drawable.ic_mochi)
                    .setContentTitle(lead?.pillName ?: "Coucou").setContentText(lead?.statusText ?: headline).build(),
            )
        if (lead != null && lead.needsApproval && lead.approvalFingerprint.isNotEmpty()) {
            builder.addAction(action(context, "Deny", DecisionReceiver.DENY, lead.approvalFingerprint, lead.id))
            builder.addAction(action(context, "Allow", DecisionReceiver.ALLOW, lead.approvalFingerprint, lead.id))
        }
        return builder.build()
    }

    // ── After every change ──────────────────────────────────────────────────────

    fun sync(context: Context, before: List<SessionItem>, now: List<SessionItem>) {
        val nm = manager(context)
        nm.notify(LIVE_ID, live(context, now))
        Widgets.update(context)
        // No permission: nothing below would show, and nothing should be marked as told.
        if (!nm.areNotificationsEnabled()) return

        // A command waits for your OK.
        val waiting = now.firstOrNull { it.needsApproval && it.approvalFingerprint.isNotEmpty() }
        if (waiting == null) {
            nm.cancel(APPROVAL_ID)
        } else if (notified.add(waiting.approvalFingerprint) && !MainActivity.isOpen) {
            val text = if (waiting.approvalCommand.isEmpty()) "An agent is waiting for your OK"
            else "Waiting for your OK: ${waiting.approvalCommand.take(140)}"
            nm.notify(
                APPROVAL_ID,
                base(context, channel("approval"), waiting)
                    .setContentTitle("${waiting.pillName} · ${waiting.title}")
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    // Review, or a tap: the command opens in the app, where Allow asks for the owner.
                    .setContentIntent(open(context, waiting.id, waiting.approvalFingerprint))
                    .addAction(action(context, "Allow", DecisionReceiver.ALLOW, waiting.approvalFingerprint, waiting.id))
                    .addAction(Notification.Action.Builder(null as Icon?, "Review", open(context, waiting.id, waiting.approvalFingerprint)).build())
                    .addAction(action(context, "Deny", DecisionReceiver.DENY, waiting.approvalFingerprint, waiting.id))
                    .setAutoCancel(true)
                    .build(),
            )
        }

        // A question: one button per choice when it is a single question with a single pick.
        val asking = now.firstOrNull { it.questionPayload != null && it.questionFingerprint.isNotEmpty() }
        val payload = asking?.questionPayload
        if (asking == null || payload == null) {
            nm.cancel(QUESTION_ID)
        } else if (notified.add("q-${asking.questionFingerprint}") && !MainActivity.isOpen) {
            val first = payload.items.first()
            val text = first.question + "\n" + first.options.joinToString("\n") { "• ${it.label}" }
            val builder = base(context, channel("question"), asking)
                .setContentTitle("${asking.pillName} has a question")
                .setContentText(first.question)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setAutoCancel(true)
            // Several questions, or several picks: answered in the app. Android shows three actions.
            if (payload.items.size == 1 && !first.multiSelect) {
                for (option in first.options.take(3)) {
                    builder.addAction(action(context, option.label, DecisionReceiver.PICK, asking.questionFingerprint, asking.id, option.label))
                }
            }
            nm.notify(QUESTION_ID, builder.build())
        }

        // An agent finished or failed: each is notified once.
        for (session in now) {
            val key = when (session.state) {
                BotState.FINISHED -> "finished"
                BotState.ERROR -> "error"
                else -> session.state.name
            }
            val was = lastSeen.put(session.id, key)
            if (was == key || (key != "finished" && key != "error")) continue
            if (MainActivity.isOpen || !Prefs.notifyDone) continue
            val quiet = Prefs.isQuiet()
            val text = if (key == "finished") {
                val answer = Link.turns[session.id]?.finalMessage?.ifEmpty { null } ?: session.finalLine
                if (answer.isEmpty()) "Done. What's next?" else "✓ " + answer.take(220)
            } else {
                session.finalLine.take(220).ifEmpty { "Something went wrong." }
            }
            nm.notify(
                DONE_BASE + Math.floorMod(session.id.hashCode(), 10_000),
                base(context, if (quiet) QUIET else channel(if (key == "finished") "finish" else "error"), session)
                    .setContentTitle("${session.pillName} · ${session.title}")
                    .setContentText(text)
                    .setStyle(Notification.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .build(),
            )
        }
        lastSeen.keys.retainAll(now.map { it.id }.toSet())
    }
}
