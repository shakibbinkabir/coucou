// Settings and About — AboutView.swift: how the phone is linked, the links
// people look for, and the setup guide again.
//
// The iPhone is linked through iCloud. This phone is linked over the local
// Wi-Fi: what the iPhone says about iCloud is, here, the "Your computer"
// section (the link's state, this phone's address, the hooks to give the
// computer) and the three steps of the guide.

package fr.louisraille.coucou

import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

private val LINKS = listOf(
    "Website" to "https://louis-cfm.github.io/coucou/",
    "Support" to "https://louis-cfm.github.io/coucou/support.html",
)

/**
 * Settings and About: how the phone is linked, the links people look for, and
 * the setup guide again.
 */
@Composable
fun AboutView() {
    val nav = LocalNav.current
    val context = LocalContext.current
    var showGuide by remember { mutableStateOf(false) }
    var confirmToken by remember { mutableStateOf(false) }
    var notifyDone by remember { mutableStateOf(Prefs.notifyDone) }
    var mochiSounds by remember { mutableStateOf(Prefs.mochiSounds) }
    var quietHours by remember { mutableStateOf(Prefs.quietHours) }
    var quietFrom by remember { mutableIntStateOf(Prefs.quietFrom) }
    var quietTo by remember { mutableIntStateOf(Prefs.quietTo) }
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?" }

    // The address follows the network, and notifications are allowed or not in
    // the system's settings: both are looked at again while this screen is open.
    // ponytail: a look every 2 s; a NetworkCallback and the activity's onStart if it ever shows in a profile.
    var address by remember { mutableStateOf(LinkSetup.baseUrl()) }
    LaunchedEffect(Unit) {
        val notifications = context.getSystemService(NotificationManager::class.java)
        while (true) {
            address = LinkSetup.baseUrl()
            Link.notificationsAllowed = notifications.areNotificationsEnabled()
            delay(2000)
            withFrameNanos {} // a frame only comes while the app is on screen: nothing is asked behind it
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar("Settings", onBack = { nav.pop() })
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                GroupedSection {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        MochiLive(
                            BotState.IDLE,
                            Modifier.size(64.dp).background(Ios.white(0.16f), RoundedCornerShape(16.dp)).padding(6.dp),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Coucou", style = T.title3.semibold)
                            Text("Version $version", style = T.footnote.color(Ios.secondary))
                        }
                    }
                }

                GroupedSection(
                    header = "Your computer",
                    footer = "Paste them into ~/.claude/settings.json on your computer, merged with what is already there. " +
                        "Your phone and your computer must be on the same Wi-Fi.",
                ) {
                    LinkStateRow()
                    RowSeparator()
                    ValueRow("This phone", address ?: "Not on a network")
                    RowSeparator()
                    ButtonRow("Copy the hooks for Claude Code") { copyText(context, LinkSetup.claudeHooksJson()) }
                    RowSeparator()
                    ButtonRow("Share…") { shareText(context, LinkSetup.claudeHooksJson()) }
                }
                GroupedSection(footer = "Run it on your computer: it prints {\"app\":\"coucou\"} when it reaches this phone.") {
                    ButtonRow("Copy a test command") { copyText(context, LinkSetup.testCommand()) }
                }
                GroupedSection {
                    ButtonRow("New token", Ios.red) { confirmToken = true }
                }

                GroupedSection(header = "Connection") {
                    val off = Link.notificationsAllowed == false
                    ValueRow("Notifications", if (off) "Off" else "On", if (off) Ios.orange else Ios.secondary)
                    if (off) {
                        RowSeparator()
                        ButtonRow("Open phone Settings") {
                            context.startActivity(
                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                            )
                        }
                    }
                    RowSeparator()
                    ButtonRow("How to connect your computer") { showGuide = true }
                }

                GroupedSection(
                    header = "Notifications",
                    footer = if (quietHours) {
                        "In the quiet hours, only what waits on you (a command to allow, a question) makes a sound. The rest arrives silently."
                    } else {
                        "Approvals and questions always notify you. Mochi's sounds are the ones he makes on your computer."
                    },
                ) {
                    ToggleRow("When an agent finishes or fails", notifyDone) {
                        notifyDone = it
                        Prefs.notifyDone = it
                    }
                    RowSeparator()
                    ToggleRow("Mochi's sounds", mochiSounds) {
                        mochiSounds = it
                        Prefs.mochiSounds = it
                        Notifier.soundsChanged(context)
                    }
                    RowSeparator()
                    ToggleRow("Quiet hours", quietHours) {
                        quietHours = it
                        Prefs.quietHours = it
                    }
                    if (quietHours) {
                        RowSeparator()
                        TimeRow("From", quietFrom) {
                            quietFrom = it
                            Prefs.quietFrom = it
                        }
                        RowSeparator()
                        TimeRow("To", quietTo) {
                            quietTo = it
                            Prefs.quietTo = it
                        }
                    }
                }

                GroupedSection(header = "Coucou") {
                    LINKS.forEachIndexed { index, (title, url) ->
                        if (index > 0) RowSeparator()
                        ButtonRow(title) {
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            } catch (_: Exception) {
                                Toast.makeText(context, "No app on this phone can open $url", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }

                GroupedSection {
                    Text(
                        "No account, no analytics, no ads. Your sessions go straight from your computer to this phone " +
                            "over your Wi-Fi. They pass through no server and are kept on this phone only.",
                        style = T.footnote.color(Ios.secondary),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                    )
                }
            }
        }

        if (showGuide) {
            BackHandler { showGuide = false }
            OnboardingView { showGuide = false }
        }
    }

    if (confirmToken) {
        AlertDialog(
            onDismissRequest = { confirmToken = false },
            containerColor = Ios.groupedCell,
            title = { Text("New token") },
            text = { Text("Every computer linked now will have to be linked again.") },
            confirmButton = {
                TextButton(onClick = {
                    Prefs.resetLinkToken()
                    confirmToken = false
                }) { Text("New token", color = Ios.red) }
            },
            dismissButton = { TextButton(onClick = { confirmToken = false }) { Text("Cancel") } },
        )
    }
}

/**
 * The link's state in one row, with a coloured dot; once a computer has been
 * heard, when and from which address.
 */
@Composable
private fun LinkStateRow() {
    val status = Link.status
    val (text, color) = when (status) {
        Link.Status.Starting -> "Starting…" to Ios.orange
        Link.Status.Waiting -> "Waiting for your computer" to Ios.orange
        Link.Status.Ready -> "Linked" to Ios.green
        is Link.Status.Failed -> status.message.ifEmpty { "The link could not start" } to Ios.red
    }
    SettingsRow {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(text, style = T.body.color(if (status is Link.Status.Failed) Ios.red else Ios.label))
            if (status == Link.Status.Ready && Link.lastEventAt > 0) {
                val detail = T.footnote.color(Ios.secondary)
                Row {
                    Text("Last event ", style = detail)
                    RelativeTime(Link.lastEventAt, detail)
                    Text(
                        " ago" + if (Link.lastEventFrom.isEmpty()) "" else " from ${Link.lastEventFrom}",
                        style = detail, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ── Rows of an inset grouped list ─────────────────────────────────────────────

/** A row of a grouped section: 44 high at least, as in a List. */
@Composable
private fun SettingsRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, content = content,
    )
}

/** LabeledContent: a label, and its value on the right. */
@Composable
private fun ValueRow(label: String, value: String, color: Color = Ios.secondary) {
    SettingsRow {
        Text(label, style = T.body)
        Text(value, style = T.body.color(color), textAlign = TextAlign.End, modifier = Modifier.weight(1f).padding(start = 12.dp))
    }
}

/** A row that is a button (or a Link): its title in the accent colour. */
@Composable
private fun ButtonRow(title: String, color: Color = Ios.accent, onClick: () -> Unit) {
    SettingsRow(Modifier.pressable(onClick = onClick)) {
        Text(title, style = T.body.color(color))
    }
}

/** Toggle. The whole row flips the switch, and reads as one switch with its label to TalkBack. */
@Composable
private fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val source = remember { MutableInteractionSource() }
    SettingsRow(Modifier.toggleable(checked, source, indication = null, role = Role.Switch, onValueChange = onChange)) {
        Text(title, style = T.body, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Switch(
            checked, onCheckedChange = null,
            // With something in it, the thumb keeps one size, on or off, as the iPhone's does.
            thumbContent = {},
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = Ios.green,
                uncheckedThumbColor = Color.White, uncheckedTrackColor = Ios.white(0.22f),
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

/** DatePicker for an hour and a minute: the time in its pill, a clock to change it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeRow(title: String, minutes: Int, onChange: (Int) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    SettingsRow(Modifier.pressable { picking = true }) {
        Text(title, style = T.body, modifier = Modifier.weight(1f))
        // Minutes after midnight, as a time of today.
        Text(
            clockTime(startOfToday() + minutes * 60_000L), style = T.body,
            modifier = Modifier.glassPill(RoundedCornerShape(8.dp)).padding(horizontal = 11.dp, vertical = 5.dp),
        )
    }
    if (picking) {
        val state = rememberTimePickerState(initialHour = minutes / 60, initialMinute = minutes % 60)
        AlertDialog(
            onDismissRequest = { picking = false },
            containerColor = Ios.groupedCell,
            text = {
                TimePicker(
                    state,
                    colors = TimePickerDefaults.colors(
                        timeSelectorSelectedContainerColor = Ios.accent.copy(alpha = 0.25f),
                        timeSelectorSelectedContentColor = Ios.accent,
                        periodSelectorSelectedContainerColor = Ios.accent.copy(alpha = 0.25f),
                        periodSelectorSelectedContentColor = Ios.accent,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onChange(state.hour * 60 + state.minute)
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        )
    }
}

// ── The hooks, to the computer ────────────────────────────────────────────────

/**
 * Copies `text`. The hooks and the test command carry the link's token, so the
 * system is told not to show what was copied.
 */
private fun copyText(context: Context, text: String) {
    val clip = ClipData.newPlainText("Coucou", text)
    if (Build.VERSION.SDK_INT >= 33) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    // Android 13 and later say it themselves.
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

private fun shareText(context: Context, text: String) {
    context.startActivity(
        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null),
    )
}

// ── The setup guide ───────────────────────────────────────────────────────────

/** First launch: what Coucou needs on the computer to show anything here. */
@Composable
fun OnboardingView(onDone: () -> Unit) {
    val context = LocalContext.current
    Column(
        // It covers the screen it is shown over: nothing behind it can be touched.
        Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {}.systemBarsPadding(),
    ) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MochiLive(BotState.FINISHED, Modifier.size(88.dp))
                Text("Your agents, in your pocket", style = T.largeTitle.bold)
                Text(
                    "Coucou on your phone shows what Claude Code, Cursor and Codex are doing on your computer. " +
                        "Three things to set up, once.",
                    style = T.body.color(Ios.secondary),
                )
            }
            Step(1, "Same Wi-Fi", "Put your phone and your computer on the same network.", Sym.icloud)
            Step(
                2, "Add the hooks on your computer",
                "Copy them from here and merge them into ~/.claude/settings.json.", Sym.computer,
            ) {
                Row(Modifier.padding(top = 7.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PillButton(Sym.docOnDoc, "Copy") { copyText(context, LinkSetup.claudeHooksJson()) }
                    PillButton(Sym.squareAndArrowUp, "Share…") { shareText(context, LinkSetup.claudeHooksJson()) }
                }
            }
            Step(3, "Start Claude Code", "Your session shows up here within a second.", Sym.terminal)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Then, from here", style = T.headline)
                Text(
                    "• Allow or deny a command, right from the Lock Screen\n• Answer Claude's questions\n" +
                        "• See what Claude did, file by file",
                    style = T.callout.color(Ios.secondary),
                )
            }
        }
        Box(
            Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp).fillMaxWidth()
                .background(Ios.accent, RoundedCornerShape(14.dp)).pressable(onClick = onDone).padding(vertical = 15.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Got it", style = T.headline)
        }
    }
}

@Composable
private fun Step(number: Int, title: String, text: String, icon: ImageVector, below: @Composable () -> Unit = {}) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(44.dp).background(Ios.white(0.14f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(22.dp), tint = Ios.accent)
        }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("$number. $title", style = T.headline)
            Text(text, style = T.callout.color(Ios.secondary))
            below()
        }
    }
}

@Composable
private fun PillButton(icon: ImageVector, title: String, onClick: () -> Unit) {
    Row(
        Modifier.glassPill().pressable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, null, Modifier.size(16.dp), tint = Ios.accent)
        Text(title, style = T.subheadline.semibold.color(Ios.accent))
    }
}
