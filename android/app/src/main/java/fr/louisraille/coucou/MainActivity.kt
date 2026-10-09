// The app's one window — CoucouPhoneApp.swift and HomeView in HomeView.swift:
// the four tabs, the screens pushed over them, the review sheet, the setup
// guide on first launch and the opening.

package fr.louisraille.coucou

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

enum class CoucouTab(val title: String, val icon: ImageVector) {
    AGENTS("Agents", Sym.sparkles),
    SERVICES("Services", Sym.squareGrid2x2),
    HISTORY("History", Sym.clockArrowCirclepath),
    SEARCH("Search", Sym.magnifyingglass),
}

/** A screen pushed over the tabs. */
sealed interface Screen {
    data class Session(val id: String) : Screen
    data object About : Screen
}

/** Where the app is: the tab, and the screens pushed over it (NavigationStack's path). */
class Nav {
    var tab by mutableStateOf(CoucouTab.AGENTS)
    val stack = mutableStateListOf<Screen>()

    fun push(screen: Screen) {
        if (stack.lastOrNull() != screen) stack.add(screen)
    }

    fun pop() {
        stack.removeLastOrNull()
    }

    /** A notification or a widget asked for a session. */
    fun open(pillId: String) {
        tab = CoucouTab.AGENTS
        stack.clear()
        stack.add(Screen.Session(pillId))
    }
}

val LocalNav = staticCompositionLocalOf<Nav> { error("No Nav") }

class MainActivity : ComponentActivity() {
    /** The answer to "allow notifications?": Mochi's own notification shows from then on. */
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Link.notificationsAllowed = granted
        LinkService.start(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(0), SystemBarStyle.dark(0))
        super.onCreate(savedInstanceState)
        LinkService.start(this)
        handle(intent)
        // That is how permission requests, questions and finished turns reach you.
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            Link.notificationsAllowed = true
        }
        setContent {
            MaterialTheme(colorScheme = coucouColors) {
                HomeView()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    /** A tap on a notification or a widget: that session, or the command to review. */
    private fun handle(intent: Intent?) {
        intent?.getStringExtra(EXTRA_PILL)?.let { Link.openPillId = it }
        intent?.getStringExtra(EXTRA_REVIEW)?.let { Link.reviewFingerprint = it }
    }

    override fun onStart() {
        super.onStart()
        isOpen = true
        Notifier.appOpened(this)
    }

    override fun onStop() {
        super.onStop()
        isOpen = false
    }

    companion object {
        const val EXTRA_PILL = "pillId"
        const val EXTRA_REVIEW = "reviewFingerprint"

        /** The app is on screen: what would be notified is already there. */
        @Volatile
        var isOpen = false
            private set
    }
}

/** Material's own surfaces (dialogs, sheets, menus, pickers) in the iPhone app's dark greys. */
private val coucouColors = darkColorScheme(
    primary = Ios.accent, onPrimary = Color.White, secondary = Ios.accent, tertiary = Ios.accent,
    background = Color.Black, onBackground = Ios.label,
    surface = Color.Black, onSurface = Ios.label, surfaceVariant = Ios.white(0.16f), onSurfaceVariant = Ios.secondary,
    surfaceContainerLowest = Color.Black, surfaceContainerLow = Ios.white(0.09f), surfaceContainer = Ios.groupedCell,
    surfaceContainerHigh = Ios.groupedCell, surfaceContainerHighest = Ios.white(0.16f),
    outline = Ios.separator, outlineVariant = Ios.separator, error = Ios.red,
)

@Composable
fun HomeView() {
    val nav = remember { Nav() }
    var introDone by remember { mutableStateOf(false) }
    var onboardingDone by remember { mutableStateOf(Prefs.onboardingDone) }

    LaunchedEffect(Unit) { Link.refresh() }
    // A notification or a widget asked for a session.
    LaunchedEffect(Link.openPillId) {
        Link.openPillId?.let {
            nav.open(it)
            Link.openPillId = null
        }
    }
    // The first launch opens on the setup guide instead of the opening.
    LaunchedEffect(Unit) {
        if (!onboardingDone) {
            introDone = true
            IntroLanding.landed = true
        }
    }

    CompositionLocalProvider(LocalNav provides nav) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AnimatedContent(
                targetState = nav.stack.lastOrNull(),
                transitionSpec = {
                    val forward = targetState != null && (initialState == null || nav.stack.size > 1)
                    if (forward) (slideInHorizontally { it / 3 } + fadeIn()) togetherWith fadeOut()
                    else fadeIn() togetherWith (slideOutHorizontally { it / 3 } + fadeOut())
                },
                label = "screen",
            ) { screen ->
                when (screen) {
                    null -> Tabs(nav)
                    is Screen.Session -> SessionDetailView(screen.id)
                    Screen.About -> AboutView()
                }
            }
            BackHandler(enabled = nav.stack.isNotEmpty()) { nav.pop() }

            Link.reviewFingerprint?.let { fingerprint ->
                ReviewSheet(fingerprint) { Link.reviewFingerprint = null }
            }
            // First launch: how to connect the computer.
            if (!onboardingDone) {
                OnboardingView {
                    Prefs.onboardingDone = true
                    onboardingDone = true
                }
            }
            // The opening: Mochi alone while the app loads, then he flies to his tile.
            if (!introDone) IntroView(ready = Link.firstSyncDone) { introDone = true }
        }
    }
}

@Composable
private fun Tabs(nav: Nav) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            when (nav.tab) {
                CoucouTab.AGENTS -> AgentsTab()
                CoucouTab.SERVICES -> ServicesTab()
                CoucouTab.HISTORY -> Column(Modifier.fillMaxSize().statusBarsPadding()) { HistoryView() }
                CoucouTab.SEARCH -> SearchTab()
            }
        }
        Row(
            Modifier.fillMaxWidth().background(Ios.white(0.07f)).navigationBarsPadding().padding(top = 8.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            for (tab in CoucouTab.entries) {
                val on = nav.tab == tab
                Column(
                    Modifier.weight(1f).pressable(role = Role.Tab) { nav.tab = tab },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Icon(tab.icon, null, Modifier.size(24.dp), tint = if (on) Ios.accent else Ios.gray)
                    Text(tab.title, style = T.caption2.color(if (on) Ios.accent else Ios.gray))
                }
            }
        }
    }
}

/**
 * The service Mochi (GitHub, Stripe…). On the iPhone the Mac reads those
 * services with the keys in its Keychain and publishes what it saw; nothing
 * does that for this phone yet, so the tab says so rather than staying blank.
 */
@Composable
fun ServicesTab() {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        LargeTitle("Services")
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth().glassCard().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MochiStill(BotState.SLEEPING, Modifier.size(56.dp), bodyHex = "#8C8C8C", showBadge = false)
            Text("No service yet", style = T.headline)
            Text(
                "GitHub, Vercel, Stripe and the others are read by the Coucou desktop app, with the keys it keeps. " +
                    "It does not send them to an Android phone yet.",
                style = T.subheadline.color(Ios.secondary), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}
