package io.github.usernamealreadytakensht.games.ui.draughts

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.usernamealreadytakensht.games.R
import io.github.usernamealreadytakensht.games.game.draughts.Checkers
import io.github.usernamealreadytakensht.games.game.draughts.Draughts
import io.github.usernamealreadytakensht.games.game.draughts.DraughtsResult
import io.github.usernamealreadytakensht.games.game.draughts.CheckersState
import io.github.usernamealreadytakensht.games.game.draughts.CheckersViewModel

private val LightSquare = Color(0xFFEAD9C0)
private val DarkSquare = Color(0xFF8B6B4A)
private val SelectedTint = Color(0x8020A0FF)
private val LastMoveTint = Color(0x60F5D742)
private val PathTint = Color(0x8060C060)

/** English checkers (8x8) against Marcher; same layout as [DraughtsScreen]. */
@Composable
fun CheckersScreen(
    launch: DraughtsLaunch,
    onBack: () -> Unit,
    onNewGame: () -> Unit,
    vm: CheckersViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showResign by remember { mutableStateOf(false) }

    LaunchedEffect(launch) {
        when (launch) {
            is DraughtsLaunch.NewGame -> vm.startGame(launch.config)
            DraughtsLaunch.Resume -> if (!vm.resumeGame()) onBack()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> vm.pauseClock()
                Lifecycle.Event.ON_START -> vm.resumeClock()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); vm.pauseClock() }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) }
                    Text("English checkers", style = MaterialTheme.typography.titleLarge)
                }
                Text(state.config.timeControl.label, style = MaterialTheme.typography.labelLarge)
            }

            ClockRow(state.config.opponentLabel, state.clockMs(state.engineSide), state.config.timeControl.startingMs,
                active = state.runningClock == state.engineSide, lost = state.result == DraughtsResult.PLAYER_WINS)

            Board(state, onTap = vm::onSquareTapped)

            ClockRow("You", state.clockMs(state.playerSide), state.config.timeControl.startingMs,
                active = state.runningClock == state.playerSide, lost = state.result == DraughtsResult.ENGINE_WINS)

            Row(modifier = Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.thinking) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(state.statusText, style = MaterialTheme.typography.bodyLarge,
                    color = if (state.engineError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }

            if (state.result != DraughtsResult.ONGOING) {
                GameOverBanner(state, onRematch = vm::rematch, onNewGame = onNewGame)
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::undo, enabled = state.canUndo && state.engineError == null,
                        modifier = Modifier.weight(1f)) { Text("Undo") }
                    OutlinedButton(onClick = vm::flipBoard, modifier = Modifier.weight(1f)) { Text("Flip") }
                    OutlinedButton(onClick = { showResign = true }, modifier = Modifier.weight(1f)) { Text("Resign") }
                }
            }

            MoveList(state.moves, modifier = Modifier.weight(1f))
        }
    }

    if (showResign) {
        AlertDialog(
            onDismissRequest = { showResign = false },
            title = { Text("Resign?") },
            text = { Text("The game will be recorded as a loss.") },
            confirmButton = { Button(onClick = { showResign = false; vm.resign() }) { Text("Resign") } },
            dismissButton = { TextButton(onClick = { showResign = false }) { Text("Keep playing") } },
        )
    }
}

@Composable
private fun Board(state: CheckersState, onTap: (Int) -> Unit) {
    val lastSquares = state.lastMove?.path?.toSet() ?: emptySet()
    Column(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
        for (r in 0 until 8) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                for (c in 0 until 8) {
                    val row = if (state.flipped) 7 - r else r
                    val col = if (state.flipped) 7 - c else c
                    val sq = row * 8 + col
                    val playable = Checkers.isDark(sq)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .background(if (playable) DarkSquare else LightSquare)
                            .then(if (sq in lastSquares) Modifier.background(LastMoveTint) else Modifier)
                            .then(if (sq in state.partialPath) Modifier.background(PathTint) else Modifier)
                            .then(if (state.selected == sq) Modifier.background(SelectedTint) else Modifier)
                            .then(if (playable) Modifier.clickable { onTap(sq) } else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (playable) {
                            Text("${Checkers.number(sq)}", fontSize = 9.sp, color = LightSquare,
                                modifier = Modifier.align(Alignment.TopStart).padding(start = 2.dp))
                            if (sq in state.targets) {
                                Box(modifier = Modifier.fillMaxSize(0.3f).background(Color(0x66000000), CircleShape))
                            }
                            val piece = state.squares[sq]
                            if (piece != Checkers.EMPTY) {
                                val res = when (piece) {
                                    Checkers.WHITE_MAN -> R.drawable.stone_l1
                                    Checkers.WHITE_KING -> R.drawable.stone_l2
                                    Checkers.BLACK_MAN -> R.drawable.stone_d1
                                    else -> R.drawable.stone_d2
                                }
                                Image(painterResource(res), contentDescription = null, modifier = Modifier.fillMaxSize(0.9f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClockRow(name: String, ms: Long?, startingMs: Long?, active: Boolean, lost: Boolean) {
    val warnMs = minOf(10_000L, (startingMs ?: 0L) / 5)
    val bg = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth().background(bg, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = MaterialTheme.typography.bodyLarge)
        if (ms != null) {
            Text(
                formatClock(ms),
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                color = if (lost || ms < warnMs) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun formatClock(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return if (ms < 10_000) "%d:%02d.%d".format(m, s, (ms % 1000) / 100) else "%d:%02d".format(m, s)
}

@Composable
private fun GameOverBanner(state: CheckersState, onRematch: () -> Unit, onNewGame: () -> Unit) {
    val (title, color) = when (state.result) {
        DraughtsResult.PLAYER_WINS -> "You won" to MaterialTheme.colorScheme.primaryContainer
        DraughtsResult.ENGINE_WINS -> "${state.config.engine.label} won" to MaterialTheme.colorScheme.errorContainer
        else -> "Draw" to MaterialTheme.colorScheme.surfaceVariant
    }
    Column(
        modifier = Modifier.fillMaxWidth().background(color, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text("Rematch swaps colours.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onRematch, modifier = Modifier.weight(1f)) { Text("Rematch") }
            OutlinedButton(onClick = onNewGame, modifier = Modifier.weight(1f)) { Text("New game") }
        }
    }
}

@Composable
private fun MoveList(moves: List<String>, modifier: Modifier = Modifier) {
    val rows = moves.chunked(2).mapIndexed { i, pair -> "${i + 1}. ${pair[0]}" + (pair.getOrNull(1)?.let { "   $it" } ?: "") }
    val listState = rememberLazyListState()
    LaunchedEffect(rows.size) { if (rows.isNotEmpty()) listState.animateScrollToItem(rows.size - 1) }
    LazyColumn(modifier = modifier.fillMaxWidth(), state = listState) {
        items(rows) { row -> Text(row, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp)) }
    }
}
