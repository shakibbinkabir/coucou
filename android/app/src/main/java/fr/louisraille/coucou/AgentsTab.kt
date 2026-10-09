// The Agents tab — HomeView.swift from AgentsTab down: the island that sums the
// sessions up, today's tally, every agent session, and Allow / Deny without
// opening one.

package fr.louisraille.coucou

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What an inset grouped list leaves above its first section and between two, on iOS. */
private val sectionGap = 35.dp

/** Where the header rests under the bar: that gap, and its row's own 6. */
private val headerTop = sectionGap + 6.dp

/** A list row under the finger (systemGray4). */
private val rowHighlight = Color(0xFF3A3A3C)

/** .headline.monospacedDigit(). */
private val tallyDigits = T.headline.copy(fontFeatureSettings = "tnum")

/**
 * Every agent session from the computer, most urgent first. Swipe right to
 * allow (after the owner check), left to deny; press and hold for a peek at
 * the last turn.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsTab() {
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    val sheet = rememberModalBottomSheetState()
    var refreshing by remember { mutableStateOf(false) }
    // The session held under a long press, by pill ID.
    var peekId by remember { mutableStateOf<String?>(null) }
    val sessions = Link.sessions.sortedByUrgency()

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        TopBar("Coucou") { BarButton(Sym.gearshape, "Settings") { nav.push(Screen.About) } }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = {
                scope.launch {
                    refreshing = true
                    Link.refresh()
                    refreshing = false
                }
            },
            modifier = Modifier.weight(1f),
        ) {
            LazyColumn(
                Modifier.fillMaxSize(), state = list,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = sectionGap),
            ) {
                item(key = "header") {
                    NotchHeader(
                        Modifier.padding(top = headerTop, bottom = 6.dp).graphicsLayer {
                            // Shrinks and fades as the list scrolls, like Wallet.
                            val y = headerTop.value - list.firstVisibleItemScrollOffset / density
                            val pull = min(0f, y - 8f)
                            val scale = max(0.86f, 1f + pull / 500f)
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(0.5f, 0f)
                            alpha = max(0f, 1f + pull / 160f)
                        },
                    )
                }
                item(key = "today") { TodayCard(Modifier.padding(vertical = 6.dp)) }
                item(key = "gap") { Spacer(Modifier.height(sectionGap)) }

                if (sessions.isEmpty()) item(key = "empty") { EmptySessionsView(Link.status) }
                itemsIndexed(sessions, key = { _, session -> session.id }) { index, session ->
                    val top = if (index == 0) 12.dp else 0.dp
                    val bottom = if (index == sessions.lastIndex) 12.dp else 0.dp
                    SessionListRow(
                        session, RoundedCornerShape(top, top, bottom, bottom), separator = index < sessions.lastIndex,
                        // Sessions move to their new place smoothly.
                        modifier = Modifier.animateItem(placementSpec = spring(dampingRatio = 0.8f, stiffness = 160f)),
                        open = { nav.push(Screen.Session(session.id)) },
                        peek = { peekId = session.id },
                        allow = { scope.launch { QuickDecision.allow(session) } },
                        deny = { QuickDecision.deny(session) },
                    )
                }
            }
        }
    }

    // The long press: the peek, and what it offers under it.
    val peeked = sessions.firstOrNull { it.id == peekId }
    if (peeked != null) {
        ModalBottomSheet(onDismissRequest = { peekId = null }, sheetState = sheet, containerColor = Ios.white(0.09f)) {
            SessionPeek(peeked, Link.turns[peeked.id])
            SessionMenu(
                peeked,
                allow = { scope.launch { QuickDecision.allow(peeked) } },
                close = { scope.launch { sheet.hide() }.invokeOnCompletion { peekId = null } },
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

/** Allow (after the owner check) and Deny without opening the session. */
object QuickDecision {
    suspend fun allow(session: SessionItem): Boolean {
        if (!OwnerCheck.confirm("Allow this command on your computer")) {
            Haptics.warning()
            return false
        }
        val sent = send(Decision.ALLOW, session)
        if (sent) Haptics.success() else Haptics.error()
        return sent
    }

    fun deny(session: SessionItem): Boolean {
        val sent = send(Decision.DENY, session)
        if (sent) Haptics.impact() else Haptics.error()
        return sent
    }

    private fun send(decision: Decision, session: SessionItem): Boolean = Link.decide(
        decision, session.approvalFingerprint, session.id, session.approvalCommand.ifEmpty { "Permission" }.take(200),
    )
}

/**
 * A session in the list, as a NavigationLink row: a tap opens it, a long press
 * peeks, and while it waits for an OK this phone can give, a full swipe right
 * allows and a full swipe left denies.
 */
@Composable
private fun SessionListRow(
    session: SessionItem, shape: Shape, separator: Boolean, modifier: Modifier,
    open: () -> Unit, peek: () -> Unit, allow: () -> Unit, deny: () -> Unit,
) {
    val canDecide = session.needsApproval && session.approvalFingerprint.isNotEmpty()
    // Half the row, as on iOS: Material's own 56 dp would deny on a slip of the finger.
    // ponytail: a fast flick still counts as a full swipe (Material's velocity threshold, not ours to set);
    // draw the swipe by hand (anchoredDraggable) if Deny goes off by accident on a real phone.
    val swipe = rememberSwipeToDismissBoxState(positionalThreshold = { it * 0.5f })
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val onAllow by rememberUpdatedState(allow)
    val onDeny by rememberUpdatedState(deny)

    // A full swipe decides, then the row comes back. settledValue, not currentValue:
    // that one already changes half-way across, with the finger still down.
    LaunchedEffect(swipe) {
        // A row that left the screen on its way back returns swiped (the list keeps its state):
        // it only closes, it decides nothing.
        if (swipe.settledValue != SwipeToDismissBoxValue.Settled) swipe.snapTo(SwipeToDismissBoxValue.Settled)
        snapshotFlow { swipe.settledValue }.collect { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> onAllow()
                SwipeToDismissBoxValue.EndToStart -> onDeny()
                else -> return@collect
            }
            swipe.reset()
        }
    }

    Column(modifier.clip(shape).background(Ios.groupedCell)) {
        SwipeToDismissBox(
            state = swipe,
            // No touch while the row comes back: it would cut the way back short.
            gesturesEnabled = canDecide && swipe.settledValue == SwipeToDismissBoxValue.Settled,
            backgroundContent = {
                val direction = swipe.dismissDirection
                if (direction != SwipeToDismissBoxValue.Settled) {
                    val allows = direction == SwipeToDismissBoxValue.StartToEnd
                    Box(
                        Modifier.fillMaxSize().background(if (allows) Ios.green else Ios.red).padding(horizontal = 22.dp),
                        contentAlignment = if (allows) Alignment.CenterStart else Alignment.CenterEnd,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Icon(if (allows) Sym.faceid else Sym.xmark, null, Modifier.size(22.dp), tint = Color.White)
                            Text(if (allows) "Allow" else "Deny", style = T.footnote.color(Color.White))
                        }
                    }
                }
            },
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .background(if (pressed) rowHighlight else Ios.groupedCell)
                    .combinedClickable(
                        interactionSource = source, indication = null, role = Role.Button,
                        onLongClickLabel = "More actions", onLongClick = peek, onClick = open,
                    )
                    // A list row's own insets; the chevron's glyph sits inside its 20 dp box.
                    .padding(start = 16.dp, top = 11.dp, end = 10.dp, bottom = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SessionRow(session, Modifier.weight(1f))
                Icon(Sym.chevronRight, null, Modifier.padding(start = 4.dp).size(20.dp), tint = Ios.tertiary)
            }
        }
        // Inset past the tile, to where the text starts.
        if (separator) RowSeparator(68.dp)
    }
}

/** What a long press on a session offers. */
@Composable
private fun SessionMenu(session: SessionItem, allow: () -> Unit, close: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val answer = Link.turns[session.id]?.finalMessage.orEmpty()

    if (session.needsApproval && session.approvalFingerprint.isNotEmpty()) {
        SessionMenuRow("Allow", Sym.faceid) {
            close()
            allow()
        }
        SessionMenuRow("Deny", Sym.xmark, Ios.red) {
            close()
            QuickDecision.deny(session)
        }
    }
    if (session.approvalCommand.isNotEmpty()) {
        SessionMenuRow("Copy the command", Sym.docOnDoc) {
            clipboard.setText(AnnotatedString(session.approvalCommand))
            close()
        }
    }
    if (answer.isNotEmpty()) {
        SessionMenuRow("Copy Claude's answer", Sym.textQuote) {
            clipboard.setText(AnnotatedString(answer))
            close()
        }
    }
    SessionMenuRow("Open", Sym.arrowUpForwardApp) {
        close()
        Link.openPillId = session.id
    }
}

/** One action of the menu: its name, its symbol at the end, as in an iOS menu. */
@Composable
private fun SessionMenuRow(title: String, icon: ImageVector, tint: Color = Ios.label, onClick: () -> Unit) {
    RowSeparator(18.dp)
    Row(
        Modifier.fillMaxWidth().pressable(onClick = onClick).padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = T.body.color(tint), modifier = Modifier.weight(1f))
        Icon(icon, null, Modifier.size(22.dp), tint = tint)
    }
}

/** The peek of a long press: who, where it is, and the last turn in short. */
@Composable
private fun SessionPeek(session: SessionItem, turn: TurnSnapshot?) {
    Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MochiStill(
                session.state,
                Modifier.size(48.dp).background(mochiTile(session.color), RoundedCornerShape(13.dp)).padding(6.dp),
                bodyHex = session.color,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(session.title, style = T.headline)
                Text(session.statusText, style = T.subheadline.semibold.color(session.statusColor))
            }
        }
        if (session.needsApproval && session.approvalCommand.isNotEmpty()) {
            Text(
                session.approvalCommand, style = T.callout.mono, maxLines = 5, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp)).padding(10.dp),
            )
        }
        if (turn != null) {
            Text(
                turn.headline, style = T.callout, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().background(Ios.accent.copy(alpha = 0.25f), RoundedCornerShape(12.dp)).padding(10.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                PeekCount(Sym.wrenchAndScrewdriver, "Actions", turn.actions.size)
                PeekCount(Sym.docText, "Files", turn.files.size)
            }
            if (turn.finalMessage.isNotEmpty()) {
                Text(turn.finalMessage, style = T.callout.color(Ios.secondary), maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A count next to its symbol, small and grey. */
@Composable
private fun PeekCount(icon: ImageVector, description: String, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(icon, description, Modifier.size(14.dp), tint = Ios.secondary)
        Text("$count", style = T.caption.color(Ios.secondary))
    }
}

/**
 * The black island at the top: Mochi with the most urgent state and a
 * summary. When a command waits for your OK, Allow and Deny grow out of it
 * and fold back once answered.
 */
@Composable
private fun NotchHeader(modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf(false) }
    var approved by remember { mutableStateOf(false) }

    val sessions = Link.sessions
    val sorted = sessions.sortedByUrgency()
    // The session waiting for an OK this phone can give.
    val waiting = sorted.firstOrNull { it.needsApproval && it.approvalFingerprint.isNotEmpty() }
    val headline = when {
        Link.status is Link.Status.Failed -> "Link is down"
        else -> sessions.summary ?: if (sessions.isEmpty()) "Nothing yet" else "All quiet"
    }
    val detail = sorted.firstOrNull()?.let { "${it.title} · ${it.statusText}" }

    Box(modifier, contentAlignment = Alignment.Center) {
        Column {
            Row(
                Modifier.fillMaxWidth()
                    .background(Ios.white(0.09f), RoundedCornerShape(28.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(28.dp))
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                MochiLive(sessions.leadState, Modifier.size(52.dp).introLanding(IntroLandingKind.HEADER))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(headline, style = T.headline)
                    if (detail != null) {
                        Text(detail, style = T.subheadline.color(Color.White.copy(alpha = 0.6f)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            // The panel slides out from under the island, and back in once answered.
            AnimatedContent(
                targetState = waiting,
                contentKey = { it?.approvalFingerprint },
                transitionSpec = {
                    (slideInVertically(spring(dampingRatio = 0.7f, stiffness = 130f)) { -it } + fadeIn()) togetherWith
                        (slideOutVertically { -it } + fadeOut()) using
                        SizeTransform { _, _ -> spring(dampingRatio = 0.7f, stiffness = 130f) }
                },
                modifier = Modifier.fillMaxWidth(),
                label = "panel",
            ) { session ->
                if (session != null) {
                    Box(Modifier.padding(top = 12.dp).fillMaxWidth().background(Ios.white(0.11f), RoundedCornerShape(26.dp))) {
                        ApprovalPanel(
                            session,
                            // Folding back, the panel of a request already answered takes no more taps.
                            disabled = sending || session.approvalFingerprint != waiting?.approvalFingerprint,
                            deny = { QuickDecision.deny(session) },
                            allow = {
                                scope.launch {
                                    sending = true
                                    if (QuickDecision.allow(session)) {
                                        approved = true
                                        delay(1400)
                                        approved = false
                                    }
                                    sending = false
                                }
                            },
                        )
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = approved,
            enter = scaleIn(spring(dampingRatio = 0.7f, stiffness = 250f)) + fadeIn(),
            exit = scaleOut() + fadeOut(),
        ) {
            // .ultraThinMaterial on the iPhone: no blur here, a dark fill.
            Box(Modifier.background(Ios.white(0.16f).copy(alpha = 0.92f), CircleShape).padding(14.dp)) {
                DrawnCheckmark(64.dp)
            }
        }
    }
}

/** A session as one line: its Mochi on the agent's colour, its name, its agent, where it is and since when. */
@Composable
fun SessionRow(session: SessionItem, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(40.dp).background(mochiTile(session.color), RoundedCornerShape(11.dp)).padding(4.dp)) {
            // VS Code's Mochi is where the intro's Mochi lands.
            MochiLive(session.state, if (session.id == PillCatalog.MAIN) Modifier.introLanding(IntroLandingKind.TILE) else Modifier)
        }
        // The 20 is the Swift's Spacer(minLength: 8), between two 12 pt gaps.
        Column(Modifier.weight(1f).padding(end = 20.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(session.title, style = T.body.semibold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            // The agent under the project name; for a session without a project
            // name (title is the agent already), what runs in it.
            Text(
                if (session.title == session.pillName) PillCatalog.definition(session.id)?.sessionSubtitle ?: session.pillName
                else session.pillName,
                style = T.subheadline.color(Ios.secondary),
            )
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                StateSymbol(session)
                Text(
                    session.statusText,
                    style = (if (session.isWaitingForYou) T.subheadline.semibold else T.subheadline).color(session.statusColor),
                )
            }
            RelativeTime(session.updatedAt, T.caption2.color(Ios.tertiary))
        }
    }
}

@Composable
private fun EmptySessionsView(status: Link.Status) {
    val message = when (status) {
        Link.Status.Starting -> "Starting the link…"
        Link.Status.Waiting -> "No computer linked yet. Tap the gear to link yours."
        is Link.Status.Failed -> status.message
        Link.Status.Ready -> "No session yet. Start Claude Code, Cursor or Codex on your computer."
    }
    Box(Modifier.fillMaxWidth().glassCard().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, style = T.subheadline.color(Ios.secondary), textAlign = TextAlign.Center)
    }
}

/**
 * Today, as this phone saw it: turns finished, files and lines changed,
 * commands you answered. Counted on the phone, never sent anywhere.
 */
@Composable
private fun TodayCard(modifier: Modifier = Modifier) {
    val tally = Link.archive.currentTally
    val today = startOfToday()
    val decisionsToday = Link.history.count { it.date >= today }
    if (tally.isEmpty && decisionsToday == 0) return

    Box(modifier.padding(top = 10.dp)) {
        Row(Modifier.fillMaxWidth().glassCard().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TodayStat("${tally.turns}", if (tally.turns == 1) "turn" else "turns")
            TodayStat("${tally.files}", if (tally.files == 1) "file" else "files")
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(Modifier.shrinkToFit(0.6f), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("+${tally.added}", style = tallyDigits.color(Ios.green), maxLines = 1)
                    Text("−${tally.removed}", style = tallyDigits.color(Ios.red), maxLines = 1)
                }
                Text("lines", style = T.caption2.color(Ios.secondary))
            }
            TodayStat("$decisionsToday", if (decisionsToday == 1) "OK given" else "OKs given")
        }
        Text(
            "TODAY", style = T.caption2.bold.color(Ios.tertiary),
            modifier = Modifier.offset(y = (-16).dp).padding(horizontal = 10.dp),
        )
    }
}

@Composable
private fun RowScope.TodayStat(value: String, label: String) {
    Column(
        Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(value, style = tallyDigits, maxLines = 1)
        Text(label, style = T.caption2.color(Ios.secondary))
    }
}

/** .minimumScaleFactor(): what is wider than its place shrinks to fit, down to `minimum`. */
private fun Modifier.shrinkToFit(minimum: Float): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(Constraints())
    val scale = (constraints.maxWidth.toFloat() / max(1, placeable.width)).coerceIn(minimum, 1f)
    layout((placeable.width * scale).roundToInt(), (placeable.height * scale).roundToInt()) {
        placeable.placeWithLayer(0, 0) {
            scaleX = scale
            scaleY = scale
            transformOrigin = TransformOrigin(0f, 0f)
        }
    }
}
