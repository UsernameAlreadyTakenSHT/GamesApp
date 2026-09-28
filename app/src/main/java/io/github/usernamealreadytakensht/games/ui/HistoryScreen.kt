package io.github.usernamealreadytakensht.games.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.usernamealreadytakensht.games.game.record.Exports
import io.github.usernamealreadytakensht.games.game.record.GameRecord
import io.github.usernamealreadytakensht.games.game.record.HistoryRepository
import io.github.usernamealreadytakensht.games.game.record.PlayerResult

private val WinColor = Color(0xFF2E7D32)
private val LossColor = Color(0xFFC62828)
private val DrawColor = Color(0xFF757575)

private fun PlayerResult.color() = when (this) {
    PlayerResult.WIN -> WinColor
    PlayerResult.LOSS -> LossColor
    PlayerResult.DRAW -> DrawColor
}

/** Wins / draws / losses of a set of games. */
private class Score(games: List<GameRecord>) {
    val wins = games.count { it.result == PlayerResult.WIN }
    val draws = games.count { it.result == PlayerResult.DRAW }
    val losses = games.count { it.result == PlayerResult.LOSS }
    val total = games.size
    /** Points percentage, a draw counting half. */
    val percent: Int get() = if (total == 0) 0 else ((wins + draws / 2.0) * 100 / total).toInt()
    val line: String get() = "$wins W · $draws D · $losses L"
}

/** Name shown for a record: its rule set when the family has several. */
private val GameRecord.title: String get() = variant ?: game

/**
 * Finished games and statistics: overall score, score per game (tap to see it per opponent),
 * then the games, newest first. A game opens its details, with Share and Delete.
 */
@Composable
fun HistoryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { HistoryRepository(context) }
    var records by remember { mutableStateOf(repo.all()) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var opened by remember { mutableStateOf<GameRecord?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) }
                    Text("History", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    if (records.isNotEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Clear") }
                }
            }

            if (records.isEmpty()) {
                item {
                    Text(
                        "No finished games yet. Games you win, lose or draw show up here, with your stats.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
                return@LazyColumn
            }

            item { OverallCard(Score(records)) }

            item { SectionTitle("By game") }
            val byGame = records.groupBy { it.title }.toList().sortedByDescending { it.second.size }
            items(byGame, key = { "game-" + it.first }) { (title, games) ->
                GameScoreCard(
                    title = title,
                    games = games,
                    open = title in expanded,
                    onToggle = { expanded = if (title in expanded) expanded - title else expanded + title },
                )
            }

            item { SectionTitle("Games") }
            items(records, key = { it.endedAt }) { r -> RecordRow(r, onClick = { opened = r }) }
            item { Spacer(Modifier.padding(8.dp)) }
        }
    }

    opened?.let { r ->
        RecordDialog(
            record = r,
            onShare = { shareGame(context, r.export) },
            onDelete = {
                repo.delete(r.endedAt)
                records = repo.all()
                opened = null
            },
            onDismiss = { opened = null },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear history?") },
            text = { Text("All ${records.size} recorded games and their stats will be deleted.") },
            confirmButton = {
                Button(onClick = { repo.clear(); records = emptyList(); confirmClear = false }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun OverallCard(score: Score) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("${score.total} game${if (score.total > 1) "s" else ""}", style = MaterialTheme.typography.titleLarge)
                Text(score.line, style = MaterialTheme.typography.bodyMedium)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${score.percent}%", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("score", style = MaterialTheme.typography.labelSmall)
            }
        }
        ResultBar(score, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp))
    }
}

/** A thin bar split into wins, draws and losses. */
@Composable
private fun ResultBar(score: Score, modifier: Modifier = Modifier) {
    if (score.total == 0) return
    Row(modifier = modifier.fillMaxWidth().heightIn(min = 6.dp).background(Color.Transparent, RoundedCornerShape(3.dp))) {
        listOf(score.wins to WinColor, score.draws to DrawColor, score.losses to LossColor).forEach { (n, c) ->
            if (n > 0) Box(Modifier.weight(n.toFloat()).heightIn(min = 6.dp).background(c))
        }
    }
}

@Composable
private fun GameScoreCard(title: String, games: List<GameRecord>, open: Boolean, onToggle: () -> Unit) {
    val score = Score(games)
    Card(onClick = onToggle) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${score.total} game${if (score.total > 1) "s" else ""} · ${score.line}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text("${score.percent}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(if (open) "  ▲" else "  ▼", style = MaterialTheme.typography.labelMedium)
            }
            ResultBar(score)
            if (open) {
                games.groupBy { it.engine }.toList().sortedByDescending { it.second.size }.forEach { (engine, list) ->
                    val s = Score(list)
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text("vs $engine", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${s.line} · ${s.percent}%", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordRow(r: GameRecord, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(6.dp).heightIn(min = 36.dp).background(r.result.color(), RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("${r.title} · vs ${r.engine}", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${Exports.readableDate(r.endedAt)} · ${r.playerSide} · ${r.moveCount} moves",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(r.result.label, color = r.result.color(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RecordDialog(record: GameRecord, onShare: () -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${record.title} · ${record.result.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("vs ${record.opponent}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${Exports.readableDate(record.endedAt)} · you played ${record.playerSide.lowercase()} · ${record.moveCount} moves",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (record.reason.isNotEmpty()) Text(record.reason, style = MaterialTheme.typography.bodySmall)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(8.dp),
                ) {
                    Text(record.export.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { Button(onClick = onShare) { Text("Share") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        },
    )
}
