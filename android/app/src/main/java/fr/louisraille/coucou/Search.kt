// The Search tab — SearchTab in SearchAndSpotlight.swift: every turn this phone
// saw (prompts, answers, file names). The iPhone also puts finished turns in
// Spotlight; nothing here does.

package fr.louisraille.coucou

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

private class SearchHit(val turn: TurnSnapshot, val agent: String, val snippet: String)

/** The Search tab: every turn this phone saw (prompts, answers, file names). */
@Composable
fun SearchTab() {
    val nav = LocalNav.current
    var query by remember { mutableStateOf("") }

    val allTurns = (Link.turns.values + Link.archive.past.values.flatten()).sortedByDescending { it.startedAt }
    val needle = query.trim()
    val hits = allTurns.mapNotNull { turn ->
        val agent = PillCatalog.definition(turn.pillId)?.name ?: turn.pillId
        if (needle.isEmpty()) return@mapNotNull SearchHit(turn, agent, turn.headline)
        val fields = listOf(turn.prompt, turn.finalMessage, turn.project, agent) + turn.files.map { it.path }
        val field = fields.firstOrNull { it.contains(needle, ignoreCase = true) } ?: return@mapNotNull null
        SearchHit(turn, agent, snippet(field, needle))
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        LargeTitle("Search")
        SearchField(query) { query = it }
        // ponytail: every row is composed, not only the ones on screen. A phone keeps a handful of
        // turns per session (the last one and 4 past); a LazyColumn with per-row corners if that grows.
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 24.dp),
        ) {
            if (allTurns.isEmpty()) {
                Unavailable(
                    Sym.textMagnifyingglass, "No turn yet",
                    "What Claude does on your computer shows up here, ready to search.",
                )
            } else if (hits.isEmpty()) {
                // ContentUnavailableView.search(text:), in the system's words.
                Unavailable(Sym.magnifyingglass, "No Results for “$query”", "Check the spelling or try a new search.")
            } else {
                GroupedSection(header = if (query.isEmpty()) "Recent turns" else "Results") {
                    hits.forEachIndexed { index, hit ->
                        if (index > 0) RowSeparator(inset = 62.dp)
                        HitRow(hit) { nav.push(Screen.Session(hit.turn.pillId)) }
                    }
                }
            }
            // Room to bring the last results above the keyboard.
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.ime))
        }
    }
}

/** The field of .searchable: a magnifying glass, the prompt, and a cross to empty it. */
@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    BasicTextField(
        query, onChange,
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(36.dp)
            .glassPill(RoundedCornerShape(10.dp)).padding(horizontal = 8.dp),
        textStyle = T.body, singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        cursorBrush = SolidColor(Ios.accent),
        decorationBox = { field ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Sym.magnifyingglass, null, Modifier.size(20.dp), tint = Ios.secondary)
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Prompts, answers, files", style = T.body.color(Ios.secondary), maxLines = 1)
                    field()
                }
                if (query.isNotEmpty()) {
                    Icon(Sym.xmarkCircleFill, "Clear", Modifier.size(18.dp).pressable { onChange("") }, tint = Ios.secondary)
                }
            }
        },
    )
}

@Composable
private fun HitRow(hit: SearchHit, onClick: () -> Unit) {
    val pill = PillCatalog.definition(hit.turn.pillId)
    Row(
        Modifier.pressable(onClick = onClick).fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MochiStill(
            BotState.IDLE,
            Modifier.size(34.dp).background(mochiTile(pill?.color ?: "#3B4A6B"), RoundedCornerShape(9.dp)).padding(4.dp),
            bodyHex = pill?.color ?: "#FFFFFF",
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (hit.turn.project.isEmpty()) hit.agent else "${hit.turn.project} · ${hit.agent}",
                    style = T.subheadline.semibold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 4.dp),
                )
                // "5 minutes ago", "Yesterday": .relative(presentation: .named), in the phone's words.
                Text(
                    DateUtils.getRelativeTimeSpanString(
                        hit.turn.startedAt, System.currentTimeMillis(), DateUtils.SECOND_IN_MILLIS,
                    ).toString(),
                    style = T.caption2.color(Ios.tertiary), maxLines = 1,
                )
            }
            Text(hit.snippet, style = T.callout.color(Ios.secondary), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        // The chevron of a NavigationLink.
        Icon(Sym.chevronRight, null, Modifier.size(20.dp).align(Alignment.CenterVertically), tint = Ios.tertiary)
    }
}

/** ContentUnavailableView, as a List shows it: a symbol, a title and a line in one cell. */
@Composable
private fun Unavailable(icon: ImageVector, title: String, text: String) {
    GroupedSection {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, null, Modifier.size(48.dp), tint = Ios.secondary)
            Text(title, style = T.title2.bold, textAlign = TextAlign.Center)
            Text(text, style = T.subheadline.color(Ios.secondary), textAlign = TextAlign.Center)
        }
    }
}

/** A few words around the match. */
private fun snippet(text: String, needle: String): String {
    val at = text.indexOf(needle, ignoreCase = true)
    if (at < 0) return text.take(140)
    var start = max(0, at - 50)
    var end = min(text.length, at + needle.length + 90)
    // Never through the middle of an emoji (two chars in a String).
    if (start > 0 && text[start].isLowSurrogate()) start--
    if (end < text.length && text[end].isLowSurrogate()) end++
    return (if (start > 0) "…" else "") + text.substring(start, end).replace("\n", " ") + (if (end < text.length) "…" else "")
}
