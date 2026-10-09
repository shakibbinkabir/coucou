// One session on screen — SessionDetailView.swift, LastTurnView.swift,
// ApprovalCard.swift (with the review sheet and the History tab),
// QuestionCard.swift, and the words of the card TurnShare.swift draws.
//
// Two things are not the iPhone's, both because of where this phone stands.
// A file's diff and an earlier turn are pushed inside the session's own screen:
// MainActivity's Nav only knows whole sessions. And a card answered from here
// is held a moment (rememberHeld): no Mac takes a few seconds to say it heard.

package fr.louisraille.coucou

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.node.Ref
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.DateFormat
import java.util.Date
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long an answered card stays on screen, in ms (rememberHeld). */
private const val HELD_MS = 2500L

/** "What it did" shows this many actions until "Show all". */
private const val COLLAPSED_ACTIONS = 12

/** .monospacedDigit(). */
private val TextStyle.digits get() = copy(fontFeatureSettings = "tnum")

/** .spring(duration: 0.5, bounce: 0.2), the one a new phase moves with. */
private fun <T> phaseSpring() = spring<T>(dampingRatio = 0.8f, stiffness = 158f)

// ── The session ───────────────────────────────────────────────────────────────

/** One session: what the agent is doing, its plan, and what it waits for. */
@Composable
fun SessionDetailView(sessionId: String) {
    // What the iPhone pushes on its NavigationStack: an earlier turn, a file's diff.
    var pastTurn by remember { mutableStateOf<TurnSnapshot?>(null) }
    var diff by remember { mutableStateOf<TurnFile?>(null) }
    // Coming back finds a screen as it was left: scrolled, unfolded.
    val kept = rememberSaveableStateHolder()
    val back: () -> Unit = {
        if (diff != null) {
            diff = null
        } else {
            pastTurn?.let { kept.removeState("turn-${it.startedAt}") }
            pastTurn = null
        }
    }
    BackHandler(enabled = diff != null || pastTurn != null, onBack = back)

    val pushed: Any? = diff ?: pastTurn
    AnimatedContent(
        targetState = pushed,
        transitionSpec = {
            // As MainActivity moves between its screens: in from the right, back out to it.
            val forward = targetState is TurnFile || initialState == null
            if (forward) (slideInHorizontally { it / 3 } + fadeIn()) togetherWith fadeOut()
            else fadeIn() togetherWith (slideOutHorizontally { it / 3 } + fadeOut())
        },
        label = "pushed",
    ) { shown ->
        when (shown) {
            is TurnFile -> FileDiffView(shown, onClose = back)
            is TurnSnapshot -> kept.SaveableStateProvider("turn-${shown.startedAt}") {
                PastTurnScreen(shown, back) { diff = it }
            }
            else -> kept.SaveableStateProvider("session") {
                SessionScreen(sessionId, openTurn = { pastTurn = it }, openFile = { diff = it })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionScreen(sessionId: String, openTurn: (TurnSnapshot) -> Unit, openFile: (TurnFile) -> Unit) {
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = Link.sessions.firstOrNull { it.id == sessionId }
    val turn = Link.turns[sessionId]
    val pastTurns = Link.archive.past[sessionId] ?: emptyList()
    val finished = turn?.takeIf { it.endedAt != null }
    var refreshing by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // The agent's color, moving softly behind the top of the screen. It
        // fades out downwards: on black, darkening it is the iPhone's mask.
        if (session != null) {
            AgentBackdrop(
                session.color.ifEmpty { PillCatalog.definition(session.id)?.color ?: "#C0C4CC" },
                Modifier.height(360.dp).drawWithContent {
                    drawContent()
                    drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f), Color.Black)))
                },
            )
        }
        Column(Modifier.fillMaxSize()) {
            TopBar(session?.title ?: "Session", onBack = { nav.pop() }) {
                // The last turn, to show what Claude did. A picture on the iPhone, its words here.
                if (finished != null) BarButton(Sym.squareAndArrowUp, "Share") { shareTurn(context, finished) }
            }
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
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp)) {
                    when {
                        session != null -> SessionBody(session, turn, pastTurns, openTurn, openFile)
                        turn != null -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("This session ended on your computer. Its last turn:", style = T.subheadline.color(Ios.secondary))
                            LastTurnView(turn, working = false, openFile)
                            if (pastTurns.isNotEmpty()) EarlierTurns(pastTurns, openTurn)
                        }
                        else -> Text(
                            "This session ended on your computer.", style = T.body.color(Ios.secondary),
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(24.dp),
                        )
                    }
                }
            }
            // The iPhone's InstructionComposer ("Tell Claude what to do next…") sits here, at the
            // bottom like a chat. No session accepts instructions on Android yet: left out.
        }
    }
}

@Composable
private fun SessionBody(
    session: SessionItem, turn: TurnSnapshot?, pastTurns: List<TurnSnapshot>,
    openTurn: (TurnSnapshot) -> Unit, openFile: (TurnFile) -> Unit,
) {
    val held = rememberHeld()
    // What waits on you, or what you just answered.
    val asking = if (session.isWaitingForYou) session else held.value ?: session
    val approval = asking.takeIf { it.needsApproval }
    val question = asking.takeIf { !it.needsApproval && it.questionPayload != null && it.questionFingerprint.isNotEmpty() }

    Column {
        SessionHeader(session)
        Spacer(Modifier.height(16.dp))
        // A new phase (working, question, waiting, done) slides in instead of jumping.
        PhaseCard(approval) { asked -> ApprovalCard(asked) { held.value = asked } }
        PhaseCard(question) { asked ->
            asked.questionPayload?.let { payload -> QuestionCard(asked, payload) { held.value = asked } }
        }
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (approval == null && question == null && asking.question.isNotEmpty()) {
                WaitingCard(
                    "Question", asking.question, Ios.cyan,
                    "Answer on your computer: this question can't be answered from the phone.",
                )
            }
            if (turn != null) {
                LastTurnView(turn, session.isWorking, openFile)
            } else if (session.steps.isNotEmpty()) {
                PlanCard(session)
            }
            if (turn == null && session.finalLine.isNotEmpty()) {
                TitledCard("Last message") {
                    SelectionContainer { Text(session.finalLine, style = T.callout) }
                }
            }
            if (pastTurns.isNotEmpty()) EarlierTurns(pastTurns, openTurn)
        }
    }
}

/**
 * A request answered from here, kept on screen for a moment. On the iPhone the
 * card stays until the Mac publishes the session again, a few seconds later;
 * here the session changes the instant the answer leaves, and the card would
 * be gone before "sent to your computer" could be read.
 */
@Composable
private fun rememberHeld(): MutableState<SessionItem?> {
    val held = remember { mutableStateOf<SessionItem?>(null) }
    LaunchedEffect(held.value) {
        if (held.value != null) {
            delay(HELD_MS)
            held.value = null
        }
    }
    return held
}

/**
 * A card that comes with a phase (an OK to give, a question): drops in from
 * above while the cards below make room, and shrinks away when it leaves.
 */
@Composable
private fun <T : Any> PhaseCard(value: T?, content: @Composable (T) -> Unit) {
    // What it showed last, to draw while it leaves.
    val last = remember { Ref<T>() }
    if (value != null) last.value = value
    val shown = value ?: last.value
    AnimatedVisibility(
        visible = value != null,
        enter = expandVertically(phaseSpring()) + fadeIn(phaseSpring()),
        exit = scaleOut(targetScale = 0.95f) + shrinkVertically() + fadeOut(),
    ) {
        if (shown != null) Box(Modifier.padding(bottom = 16.dp)) { content(shown) }
    }
}

@Composable
private fun SessionHeader(session: SessionItem) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        MochiLive(
            session.state,
            Modifier.size(84.dp).background(mochiTile(session.color), RoundedCornerShape(22.dp)).padding(10.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // "VS Code · coucou", or just "VS Code" when there's no project name.
            Text(
                if (session.title == session.pillName) session.pillName else "${session.pillName} · ${session.title}",
                style = T.headline,
            )
            Text(session.statusText, style = T.subheadline.semibold.color(session.statusColor))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (session.macName.isNotEmpty()) {
                    Text(
                        session.macName, style = T.caption.color(Ios.secondary), maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    Text("·", style = T.caption.color(Ios.secondary))
                }
                RelativeTime(session.updatedAt, T.caption.color(Ios.secondary))
            }
            if (session.cwd.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Sym.folder, null, Modifier.size(12.dp), tint = Ios.tertiary)
                    SelectionContainer {
                        Text(
                            shortPath(session.cwd), style = T.caption2.mono.color(Ios.tertiary),
                            maxLines = 1, overflow = TextOverflow.StartEllipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * "/Users/louis/Documents/hi" → "~/Documents/hi". The computer is not always a
 * Mac here: /home/louis and C:\Users\louis are a home too.
 */
private fun shortPath(path: String): String {
    val parts = path.split('/', '\\').filter { it.isNotEmpty() }
    val home = when {
        parts.size >= 2 && (parts[0] == "Users" || parts[0] == "home") -> 2
        parts.size >= 3 && parts[0].endsWith(":") && parts[1] == "Users" -> 3
        else -> return path
    }
    return "~/" + parts.drop(home).joinToString("/")
}

/** Something the agent waits for that can only be answered on the computer. */
@Composable
private fun WaitingCard(title: String, text: String, color: Color, footnote: String) {
    Column(
        Modifier.fillMaxWidth().glassCard().border(1.5.dp, color.copy(alpha = 0.7f), RoundedCornerShape(22.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = T.subheadline.semibold.color(color))
        SelectionContainer {
            Text(
                text, style = T.callout,
                modifier = Modifier.fillMaxWidth().background(Ios.white(0.16f), RoundedCornerShape(12.dp)).padding(12.dp),
            )
        }
        Text(footnote, style = T.caption.color(Ios.secondary))
    }
}

/** The steps of a session whose turn was not recorded. */
@Composable
private fun PlanCard(session: SessionItem) {
    TitledCard("Activity · ${min(session.stepIndex + 1, session.steps.size)}/${session.steps.size}") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            session.steps.forEachIndexed { index, step ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(
                        // "circle.dotted" for the step under way and "circle" for those to come are one symbol in Sym.
                        if (index < session.stepIndex || session.state == BotState.FINISHED) Sym.checkmarkCircleFill else Sym.circleDotted,
                        null, Modifier.padding(top = 3.dp).size(15.dp),
                        tint = if (index == session.stepIndex && session.isWorking) Ios.accent else Ios.secondary,
                    )
                    Text(step, style = T.callout.color(if (index > session.stepIndex) Ios.secondary else Ios.label))
                }
            }
        }
    }
}

/** The turns before the latest one, as this phone saw them. */
@Composable
private fun EarlierTurns(pastTurns: List<TurnSnapshot>, open: (TurnSnapshot) -> Unit) {
    TitledCard("Earlier turns") {
        Column {
            pastTurns.forEachIndexed { index, past ->
                Row(
                    Modifier.fillMaxWidth().pressable { open(past) }.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(past.headline, style = T.callout, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(clockTime(past.startedAt), style = T.caption2.color(Ios.tertiary))
                    }
                    if (past.files.isNotEmpty()) {
                        Text(
                            "${past.files.size} file${if (past.files.size == 1) "" else "s"}",
                            style = T.caption.color(Ios.secondary),
                        )
                    }
                    Icon(Sym.chevronRight, null, Modifier.size(14.dp), tint = Ios.tertiary)
                }
                if (index < pastTurns.lastIndex) RowSeparator(0.dp)
            }
        }
    }
}

/** An earlier turn, on a screen of its own. */
@Composable
private fun PastTurnScreen(turn: TurnSnapshot, onBack: () -> Unit, openFile: (TurnFile) -> Unit) {
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        TopBar(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(turn.startedAt)), onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp)) {
            LastTurnView(turn, working = false, openFile)
        }
    }
}

/** A card under its title, as the session and its last turn draw them. */
@Composable
private fun TitledCard(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().glassCard().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = T.subheadline.semibold.color(Ios.secondary))
        content()
    }
}

// ── What waits on you ─────────────────────────────────────────────────────────

/**
 * The command an agent waits on, with Allow (the owner check) and Deny.
 * Used in the session screen and in the sheet opened from a notification.
 */
@Composable
private fun ApprovalCard(session: SessionItem, onSent: () -> Unit) {
    val scope = rememberCoroutineScope()
    var sending by remember { mutableStateOf<Decision?>(null) }
    // Another request starts clean.
    var sent by remember(session.approvalFingerprint) { mutableStateOf<Decision?>(null) }
    var error by remember(session.approvalFingerprint) { mutableStateOf<String?>(null) }

    fun send(decision: Decision) {
        scope.launch {
            sending = decision
            try {
                error = null
                if (decision == Decision.ALLOW && !OwnerCheck.confirm("Allow this command on your computer")) {
                    error = "Your fingerprint or screen lock didn't confirm. Nothing was sent."
                    Haptics.warning()
                    return@launch
                }
                val summary = session.approvalCommand.ifEmpty { "Permission" }
                if (Link.decide(decision, session.approvalFingerprint, session.id, summary.take(200))) {
                    sent = decision
                    onSent()
                    if (decision == Decision.ALLOW) Haptics.success() else Haptics.impact()
                } else {
                    Haptics.error()
                    // The link is local: the only way to fail is a request that no longer waits.
                    error = "This request was already answered or has expired."
                }
            } finally {
                sending = null
            }
        }
    }

    Column(
        Modifier.fillMaxWidth().glassCard().border(1.dp, Ios.orange.copy(alpha = 0.35f), RoundedCornerShape(22.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Waiting for your OK", style = T.subheadline.semibold.color(Ios.orange))
        SelectionContainer {
            Text(
                session.approvalCommand.ifEmpty { "The agent asks for a permission." }, style = T.callout.mono,
                modifier = Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.07f), RoundedCornerShape(12.dp)).padding(12.dp),
            )
        }
        AnimatedVisibility(
            visible = sent != null,
            enter = scaleIn(spring(dampingRatio = 0.7f, stiffness = 158f), initialScale = 0.8f) + fadeIn(),
            exit = ExitTransition.None,
        ) {
            val done = sent ?: return@AnimatedVisibility
            val allowed = done == Decision.ALLOW
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                // Apple Pay's "Done": the ring, then the check, draw themselves.
                if (allowed) DrawnCheckmark(34.dp) else Icon(Sym.xmarkCircleFill, null, Modifier.size(34.dp), tint = Ios.red)
                Text(
                    if (allowed) "Allowed, sent to your computer" else "Denied, sent to your computer",
                    style = T.callout.semibold.color(if (allowed) Ios.green else Ios.red),
                )
            }
        }
        if (sent == null) {
            if (session.approvalFingerprint.isEmpty()) {
                Text(
                    "Answer on your computer. This request can't be answered from the phone.",
                    style = T.caption.color(Ios.secondary),
                )
            } else {
                ApprovalChoiceButtons(disabled = sending != null, deny = { send(Decision.DENY) }, allow = { send(Decision.ALLOW) })
                Text(
                    "Allow asks for your fingerprint or screen lock. The request expires after 2 minutes.",
                    style = T.caption.color(Ios.secondary),
                )
            }
        }
        error?.let { Text(it, style = T.caption.color(Ios.red)) }
    }
}

/**
 * A question Claude asks (AskUserQuestion), with its choices: pick, then
 * Send. Claude is answered with exactly these choices.
 */
@Composable
private fun QuestionCard(session: SessionItem, payload: QuestionPayload, onSent: () -> Unit) {
    // Another question starts clean.
    var picks by remember(payload) { mutableStateOf(List(payload.items.size) { emptySet<String>() }) }
    var sent by remember(payload) { mutableStateOf(false) }
    var error by remember(payload) { mutableStateOf<String?>(null) }
    val ready = picks.none { it.isEmpty() }

    fun send() {
        error = null
        // Keep the order of the choices, as Claude listed them.
        val selections = payload.items.mapIndexed { index, item -> item.options.map { it.label }.filter { it in picks[index] } }
        if (Link.answer(session.questionFingerprint, session.id, selections)) {
            sent = true
            onSent()
            Haptics.success()
        } else {
            error = "This question was already answered or has expired."
            Haptics.error()
        }
    }

    Column(
        Modifier.fillMaxWidth().glassCard(tint = Ios.cyan).border(1.5.dp, Ios.cyan.copy(alpha = 0.7f), RoundedCornerShape(22.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Question", style = T.subheadline.semibold.color(Ios.cyan))
        payload.items.forEachIndexed { index, item ->
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (item.header.isNotEmpty()) Text(item.header.uppercase(), style = T.caption2.bold.color(Ios.secondary))
                Text(item.question, style = T.callout.semibold)
                if (item.multiSelect) Text("Pick one or more", style = T.caption.color(Ios.secondary))
                for (option in item.options) {
                    OptionRow(option, item.multiSelect, on = option.label in picks[index]) {
                        if (!sent) {
                            val mine = picks[index]
                            picks = picks.toMutableList().also {
                                it[index] = when {
                                    !item.multiSelect -> setOf(option.label)
                                    option.label in mine -> mine - option.label
                                    else -> mine + option.label
                                }
                            }
                            Haptics.impact()
                        }
                    }
                }
            }
        }
        if (sent) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Sym.checkmarkCircleFill, null, Modifier.size(18.dp), tint = Ios.green)
                Text("Answer sent to your computer", style = T.callout.semibold.color(Ios.green))
            }
        } else {
            // The system's filled button (.borderedProminent, large, cyan), grey until every question has its pick.
            val ink = if (ready) Color.White else Ios.tertiary
            Row(
                Modifier.fillMaxWidth().pressable(enabled = ready) { send() }
                    .background(if (ready) Ios.cyan else Color(0x3D767680), RoundedCornerShape(12.dp)).height(50.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Sym.paperplaneFill, null, Modifier.size(18.dp), tint = ink)
                Spacer(Modifier.width(6.dp))
                Text("Send answer", style = T.body.color(ink))
            }
            Text("Claude waits about 2 minutes, then asks in the terminal.", style = T.caption.color(Ios.secondary))
        }
        error?.let { Text(it, style = T.caption.color(Ios.red)) }
    }
}

/** One choice of a question: a radio button, or a checkbox when several can be picked. */
@Composable
private fun OptionRow(option: QuestionPayload.Option, multiSelect: Boolean, on: Boolean, pick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth()
            .pressable(role = if (multiSelect) Role.Checkbox else Role.RadioButton, onClick = pick)
            .semantics { selected = on }
            .background(if (on) Ios.cyan.copy(alpha = 0.16f) else Ios.white(0.16f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when {
            on -> Icon(
                if (multiSelect) Sym.checkmarkSquareFill else Sym.largecircleFillCircle, null,
                Modifier.size(20.dp), tint = Ios.cyan,
            )
            // "square": Sym has no empty checkbox, so it is drawn.
            multiSelect -> Box(Modifier.size(20.dp).padding(2.5.dp).border(1.5.dp, Ios.secondary, RoundedCornerShape(3.dp)))
            else -> Icon(Sym.circleDotted, null, Modifier.size(20.dp), tint = Ios.secondary)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(option.label, style = T.callout)
            if (option.description.isNotEmpty()) Text(option.description, style = T.caption.color(Ios.secondary))
        }
    }
}

/** Opened from the "Review" action of an approval notification. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewSheet(fingerprint: String, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val held = rememberHeld()
    val session = Link.sessions.firstOrNull { it.approvalFingerprint == fingerprint } ?: held.value

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Ios.white(0.09f)) {
        Box(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp)) {
            Text("Review", style = T.headline, modifier = Modifier.align(Alignment.Center))
            Text(
                "Done", style = T.body.semibold.color(Ios.accent),
                modifier = Modifier.align(Alignment.CenterEnd)
                    .pressable { scope.launch { sheet.hide() }.invokeOnCompletion { onDismiss() } }
                    .padding(start = 12.dp, top = 11.dp, bottom = 11.dp),
            )
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (session != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    MochiLive(
                        session.state,
                        Modifier.size(52.dp).background(mochiTile(session.color), RoundedCornerShape(14.dp)).padding(6.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${session.pillName} · ${session.title}", style = T.headline)
                        if (session.macName.isNotEmpty()) Text(session.macName, style = T.caption.color(Ios.secondary))
                    }
                }
                ApprovalCard(session) { held.value = session }
            } else {
                Text(
                    "This request was already answered or has expired.", style = T.body.color(Ios.secondary),
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(40.dp),
                )
            }
        }
    }
}

// ── The last turn ─────────────────────────────────────────────────────────────

/**
 * The last turn of a session: your prompt, what the agent did, the files it
 * changed (tap for the diff) and its answer.
 */
@Composable
private fun LastTurnView(turn: TurnSnapshot, working: Boolean, openFile: (TurnFile) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (turn.prompt.isNotEmpty()) PromptCard(turn)
        if (turn.actions.isNotEmpty()) ActionsCard(turn)
        if (turn.files.isNotEmpty()) FilesCard(turn.files, openFile)
        if (turn.finalMessage.isNotEmpty()) {
            TitledCard("Claude's answer") { ExpandableText(turn.finalMessage, collapsedLines = 10, markdown = true) }
        } else if (working || turn.endedAt == null) {
            TitledCard("Claude's answer") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Ios.secondary, strokeWidth = 2.dp)
                    Text("Still working…", style = T.callout.color(Ios.secondary))
                }
            }
        }
    }
}

/**
 * A background task finishing reaches Claude as a "<task-notification>"
 * prompt: shown by its summary rather than as raw XML.
 */
private fun taskSummary(prompt: String): String? {
    if (!prompt.startsWith("<task-notification>")) return null
    val start = prompt.indexOf("<summary>")
    val end = prompt.indexOf("</summary>")
    return if (start >= 0 && end >= start + 9) prompt.substring(start + 9, end) else "A background task finished"
}

@Composable
private fun PromptCard(turn: TurnSnapshot) {
    val summary = taskSummary(turn.prompt)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.End) {
        Box(
            Modifier.fillMaxWidth()
                .background(if (summary != null) Ios.white(0.16f) else Ios.accent.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
                .padding(12.dp),
        ) {
            if (summary != null) {
                ExpandableText(turn.prompt, collapsedLines = 0, title = summary, monospaced = true)
            } else {
                ExpandableText(turn.prompt, collapsedLines = 6)
            }
        }
        Text(
            "${if (summary == null) "You" else "Background task"} · ${clockTime(turn.startedAt)}",
            style = T.caption2.color(Ios.tertiary),
        )
    }
}

@Composable
private fun ActionsCard(turn: TurnSnapshot) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    val actions = if (showAll) turn.actions else turn.actions.take(COLLAPSED_ACTIONS)
    TitledCard("What it did · ${turn.actions.size}") {
        Column(Modifier.animateContentSize(tween(200, easing = LinearOutSlowInEasing))) {
            for (action in actions) ActionRow(action, action.fileIndex?.let { turn.files.getOrNull(it) })
            if (turn.actions.size > COLLAPSED_ACTIONS) {
                Text(
                    if (showAll) "Show less" else "Show all ${turn.actions.size}",
                    style = T.footnote.semibold.color(Ios.accent),
                    modifier = Modifier.padding(top = 8.dp).pressable { showAll = !showAll },
                )
            }
        }
    }
}

/** One tool call; a command opens to show what it printed. */
@Composable
private fun ActionRow(action: TurnAction, file: TurnFile?) {
    var open by rememberSaveable { mutableStateOf(false) }
    val hasOutput = action.output.isNotEmpty()
    // "pencil", "globe" and "checklist" are not in Sym: the closest that are.
    val icon = when (action.tool) {
        "Bash" -> Sym.terminal
        "Read" -> Sym.docTextMagnifyingglass
        "Edit", "MultiEdit" -> Sym.docText
        "Write" -> Sym.docBadgePlus
        "Grep", "Glob" -> Sym.magnifyingglass
        "WebFetch", "WebSearch" -> Sym.safari
        "Task", "Agent" -> Sym.person2
        "TodoWrite" -> Sym.checkmarkSquareFill
        else -> Sym.wrenchAndScrewdriver
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            Modifier.fillMaxWidth().then(if (hasOutput) Modifier.pressable { open = !open } else Modifier),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                if (action.failed) Sym.exclamationmarkTriangleFill else icon, null,
                Modifier.width(18.dp).height(16.dp), tint = if (action.failed) Ios.red else Ios.secondary,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(action.tool, style = T.caption.semibold.color(Ios.secondary))
                Text(
                    file?.name ?: action.summary, style = if (action.tool == "Bash") T.footnote.mono else T.footnote,
                    maxLines = if (open) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                )
            }
            if (file != null) {
                Text("+${file.added} −${file.removed}", style = T.caption2.digits.color(Ios.secondary))
            } else if (hasOutput) {
                Icon(
                    if (open) Sym.chevronUp else Sym.chevronDown, if (open) "Hide the output" else "Show the output",
                    Modifier.size(14.dp), tint = Ios.tertiary,
                )
            }
        }
        if (open) {
            SelectionContainer(
                Modifier.padding(start = 28.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Ios.white(0.06f))
                    .horizontalScroll(rememberScrollState()).padding(10.dp),
            ) {
                Text(action.output, style = T.caption2.mono.color(Ios.secondary))
            }
        }
    }
}

@Composable
private fun FilesCard(files: List<TurnFile>, open: (TurnFile) -> Unit) {
    TitledCard("Files changed · ${files.size}") {
        Column {
            files.forEachIndexed { index, file ->
                Row(
                    Modifier.fillMaxWidth().pressable { open(file) }.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (file.isNew) Sym.docBadgePlus else Sym.docText, null, Modifier.size(20.dp), tint = Ios.secondary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(file.name, style = T.callout.semibold)
                        Text(file.path, style = T.caption2.color(Ios.tertiary), maxLines = 1, overflow = TextOverflow.StartEllipsis)
                    }
                    Text("+${file.added}", style = T.caption.digits.color(Ios.green))
                    Text("−${file.removed}", style = T.caption.digits.color(Ios.red))
                    Icon(Sym.chevronRight, null, Modifier.size(14.dp), tint = Ios.tertiary)
                }
                if (index < files.lastIndex) RowSeparator(0.dp)
            }
        }
    }
}

/** A changed file, line by line: removed in red, added in green. */
@Composable
private fun FileDiffView(file: TurnFile, fullScreen: Boolean = false, onClose: () -> Unit) {
    var showFullScreen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // TopBar centres its title on the whole bar, where a long file name would run
        // under the counts. This bar gives the name the room that is left.
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(52.dp).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            if (fullScreen) {
                Text("Done", style = T.body.color(Ios.accent), modifier = Modifier.pressable(onClick = onClose).padding(vertical = 11.dp))
            } else {
                BarButton(Sym.chevronLeft, "Back", onClose)
            }
            Text(
                file.name, style = T.headline, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
            )
            Text("+${file.added}", style = T.footnote.semibold.digits.color(Ios.green))
            Text("−${file.removed}", style = T.footnote.semibold.digits.color(Ios.red))
            if (!fullScreen) BarButton(Sym.arrowUpLeftAndArrowDownRight, "Full screen") { showFullScreen = true }
        }
        // Every line is as wide as the longest one, and at least as wide as the
        // screen, so the green and red run from edge to edge.
        // ponytail: all the lines are composed at once (600 a file at most, as
        // the iPhone does); a LazyColumn sharing one horizontal scroll if that drags.
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())
                .navigationBarsPadding().width(IntrinsicSize.Max).padding(vertical = 8.dp),
        ) {
            for (line in file.lines) DiffRow(line)
            if (file.truncated) {
                Text("Some lines were left out to keep it light.", style = T.caption.color(Ios.secondary), modifier = Modifier.padding(12.dp))
            }
        }
    }
    // The iPhone lets this one turn sideways for long lines; here it only fills the screen.
    if (showFullScreen) {
        Dialog(onDismissRequest = { showFullScreen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            FileDiffView(file, fullScreen = true) { showFullScreen = false }
        }
    }
}

@Composable
private fun DiffRow(line: TurnDiffLine) {
    val style = T.caption.mono
    if (line.kind == DiffKind.GAP) {
        Text("⋯", style = style.color(Ios.tertiary), modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
        return
    }
    val mark = when (line.kind) {
        DiffKind.ADDED -> Ios.green
        DiffKind.REMOVED -> Ios.red
        else -> Ios.secondary
    }
    Row(
        Modifier.fillMaxWidth()
            .background(if (line.kind == DiffKind.CONTEXT) Color.Transparent else mark.copy(alpha = 0.14f))
            .padding(start = 6.dp, top = 1.dp, bottom = 1.dp),
    ) {
        Text(
            when (line.kind) {
                DiffKind.ADDED -> "+"
                DiffKind.REMOVED -> "−"
                else -> " "
            },
            style = style.color(mark), textAlign = TextAlign.Center, modifier = Modifier.width(18.dp),
        )
        Text(
            line.text.ifEmpty { " " }, style = style.color(if (line.kind == DiffKind.CONTEXT) Ios.secondary else Ios.label),
            softWrap = false, modifier = Modifier.padding(end = 16.dp),
        )
    }
}

/**
 * Long text folded to a few lines, with "Show more" / "Show less".
 * collapsedLines 0 shows only the title until opened.
 */
@Composable
private fun ExpandableText(
    text: String, collapsedLines: Int, title: String? = null, monospaced: Boolean = false, markdown: Boolean = false,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    // Short texts are shown whole, without a button.
    val isLong = collapsedLines == 0 || text.length > collapsedLines * 60 || text.count { it == '\n' } >= collapsedLines
    val shown = remember(text, markdown) { if (markdown) inlineMarkdown(text) else AnnotatedString(text) }

    Column(
        Modifier.fillMaxWidth().animateContentSize(tween(200, easing = LinearOutSlowInEasing)),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (title != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Sym.gearshape2, null, Modifier.size(18.dp), tint = Ios.label)
                Text(title, style = T.callout.semibold)
            }
        }
        if (open || collapsedLines > 0) {
            SelectionContainer {
                Text(
                    shown, style = if (monospaced) T.caption.mono.color(Ios.secondary) else T.callout,
                    maxLines = if (open || !isLong) Int.MAX_VALUE else collapsedLines, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isLong) {
            Text(
                if (open) "Show less" else if (collapsedLines == 0) "Show details" else "Show more",
                style = T.footnote.semibold.color(Ios.accent), modifier = Modifier.pressable { open = !open },
            )
        }
    }
}

/**
 * The Markdown SwiftUI's Text shows, and no more: `code`, **bold**, *italic*,
 * ~~struck~~ and [links](https://…) inside a line. Titles, lists and tables
 * stay as they were written, as on the iPhone.
 */
private val INLINE_MARKDOWN = Regex(
    """`([^`\n]+)`""" +
        """|\*\*(?!\s)(.+?)(?<!\s)\*\*""" +
        """|~~(?!\s)(.+?)(?<!\s)~~""" +
        """|\*(?![\s*])([^*\n]+?)(?<!\s)\*""" +
        // An underscore inside a word is a name (snake_case), not an emphasis.
        """|(?<!\w)_(?![\s_])([^_\n]+?)(?<!\s)_(?!\w)""" +
        // Only web addresses open from a tap.
        """|\[([^\]\n]+)\]\((https?://[^)\s]+)\)""",
)

private fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var at = 0
    for (match in INLINE_MARKDOWN.findAll(text)) {
        append(text.substring(at, match.range.first))
        val (code, bold, struck, star, underscore, label, address) = match.destructured
        when {
            code.isNotEmpty() -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(code) }
            bold.isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inlineMarkdown(bold)) }
            struck.isNotEmpty() ->
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(inlineMarkdown(struck)) }
            label.isNotEmpty() ->
                withLink(LinkAnnotation.Url(address, TextLinkStyles(SpanStyle(color = Ios.accent)))) { append(inlineMarkdown(label)) }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inlineMarkdown(star + underscore)) }
        }
        at = match.range.last + 1
    }
    append(text.substring(at))
}

// ── History ───────────────────────────────────────────────────────────────────

/** Decisions taken on this phone. */
@Composable
fun HistoryView() {
    val history = Link.history
    // Newest day first, each day's decisions newest first.
    val days = remember(history) { history.sortedByDescending { it.date }.groupBy { dayLabel(it.date) } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { LargeTitle("History") }
        if (history.isEmpty()) {
            item {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    MochiLive(BotState.SLEEPING, Modifier.size(56.dp))
                    Text("No decision yet", style = T.headline)
                    Text(
                        "Commands you allow or deny from your phone show up here. They stay on this phone.",
                        style = T.footnote.color(Ios.secondary), textAlign = TextAlign.Center,
                    )
                }
            }
        }
        for ((day, logs) in days) {
            item(key = day) {
                GroupedSection(header = day, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp)) {
                    logs.forEachIndexed { index, log ->
                        HistoryRow(log)
                        // The line starts under the text, past Mochi's tile.
                        if (index < logs.lastIndex) RowSeparator(62.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRow(log: DecisionLog) {
    val pill = PillCatalog.definition(log.pillId)
    val allowed = log.decision == Decision.ALLOW
    val color = if (allowed) Ios.green else Ios.red
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        MochiStill(
            if (allowed) BotState.FINISHED else BotState.ERROR,
            Modifier.size(34.dp).background(Ios.white(0.16f), RoundedCornerShape(9.dp)).padding(3.dp),
            bodyHex = pill?.color ?: "#FFFFFF",
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(log.summary, style = T.callout.mono, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(if (allowed) Sym.checkmarkCircleFill else Sym.xmarkCircleFill, null, Modifier.size(13.dp), tint = color)
                Text(if (allowed) "Allowed" else "Denied", style = T.caption.color(color))
                Text(
                    pill?.name ?: log.pillId, style = T.caption.color(Ios.secondary), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Text(clockTime(log.date), style = T.caption.color(Ios.tertiary))
            }
        }
    }
}

// ── Sharing a turn ────────────────────────────────────────────────────────────

/** One line for a turn: what you asked, or what woke the agent up. */
val TurnSnapshot.headline: String
    get() = taskSummary(prompt) ?: prompt.trim().ifEmpty { "Turn without a prompt" }

/** "45s", "1m 5s", "2h 3m": how long a turn took. */
private fun duration(ms: Long): String {
    val s = max(1, ms / 1000)
    val parts = if (s >= 3600) listOf(s / 3600 to "h", s % 3600 / 60 to "m") else listOf(s / 60 to "m", s % 60 to "s")
    return parts.filter { it.first > 0 }.joinToString(" ") { "${it.first}${it.second}" }
}

private fun String.clipped(length: Int) = if (this.length > length) take(length).trimEnd() + "…" else this

/**
 * A finished turn in words: what you asked, what changed, the answer. The
 * same pieces as the iPhone's share card (TurnShareCard), which is a picture:
 * it has room for four lines of the prompt and eight of the answer, and this
 * keeps about as much.
 */
private val TurnSnapshot.shareText: String
    get() = buildString {
        append(project.ifEmpty { "Claude Code" })
        endedAt?.let { append(" · Done in ${duration(it - startedAt)}") }
        append("\n\n${headline.clipped(280)}\n\n")
        append("${actions.size} ${if (actions.size == 1) "action" else "actions"} · ")
        append("${files.size} ${if (files.size == 1) "file" else "files"}")
        if (files.isNotEmpty()) {
            append(" · +${files.sumOf { it.added }} −${files.sumOf { it.removed }}")
            for (file in files.take(5)) append("\n${file.name}  +${file.added} −${file.removed}")
            if (files.size > 5) append("\nand ${files.size - 5} more")
        }
        if (finalMessage.isNotEmpty()) append("\n\n${finalMessage.clipped(700)}")
        append("\n\nCoucou")
    }

/** The system's share sheet with the turn. Made on the phone; nothing is sent anywhere else. */
private fun shareTurn(context: Context, turn: TurnSnapshot) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_TITLE, turn.project.ifEmpty { "Coucou" })
        .putExtra(Intent.EXTRA_TEXT, turn.shareText)
    context.startActivity(Intent.createChooser(send, null))
}
