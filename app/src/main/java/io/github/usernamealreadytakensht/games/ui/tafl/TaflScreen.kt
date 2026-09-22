package io.github.usernamealreadytakensht.games.ui.tafl

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.usernamealreadytakensht.games.R
import io.github.usernamealreadytakensht.games.game.Takebacks
import io.github.usernamealreadytakensht.games.game.tafl.Tafl
import io.github.usernamealreadytakensht.games.game.tafl.TaflConfig
import io.github.usernamealreadytakensht.games.game.tafl.TaflResult
import io.github.usernamealreadytakensht.games.game.tafl.TaflState
import io.github.usernamealreadytakensht.games.game.tafl.TaflViewModel

private val Square = Color(0xFFDCC9A6)
private val SquareAlt = Color(0xFFD3BE97)
private val SpecialSquare = Color(0xFF9C7B4A)
private val GridLine = Color(0x40000000)
private val SelectedTint = Color(0x8020A0FF)
private val LastMoveTint = Color(0x60F5D742)
private val CapturedTint = Color(0x70E04040)

/** How the game screen is entered: a fresh game with [config], or the saved one. */
sealed interface TaflLaunch {
    data class NewGame(val config: TaflConfig, val id: Int) : TaflLaunch
    data object Resume : TaflLaunch
}

@Composable
fun TaflScreen(
    launch: TaflLaunch,
    onBack: () -> Unit,
    onNewGame: () -> Unit,
    vm: TaflViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showResign by remember { mutableStateOf(false) }

    LaunchedEffect(launch) {
        when (launch) {
            is TaflLaunch.NewGame -> vm.startGame(launch.config)
            TaflLaunch.Resume -> if (!vm.resumeGame()) onBack()
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
                    Text(state.config.variant.label, style = MaterialTheme.typography.titleLarge)
                }
                Text(state.config.timeControl.label, style = MaterialTheme.typography.labelLarge)
            }

            ClockRow(
                "${state.config.opponentLabel} · ${sideName(state.engineSide)}", state.engineSide,
                state.clockMs(state.engineSide), state.config.timeControl.startingMs,
                active = state.runningClock == state.engineSide, lost = state.result == TaflResult.PLAYER_WINS,
            )

            if (state.squares.isNotEmpty()) Board(state, onTap = vm::onSquareTapped)

            ClockRow(
                "You · ${sideName(state.playerSide)}", state.playerSide,
                state.clockMs(state.playerSide), state.config.timeControl.startingMs,
                active = state.runningClock == state.playerSide, lost = state.result == TaflResult.ENGINE_WINS,
            )

            Row(modifier = Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.thinking) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(state.statusText, style = MaterialTheme.typography.bodyLarge)
            }

            if (state.result != TaflResult.ONGOING) {
                GameOverBanner(state, onRematch = vm::rematch, onNewGame = onNewGame)
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.config.takebacks != Takebacks.OFF) {
                        OutlinedButton(onClick = vm::undo, enabled = state.canUndo, modifier = Modifier.weight(1f)) {
                            Text(state.takebacksLeft?.let { "Undo ($it)" } ?: "Undo")
                        }
                    }
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

private fun sideName(side: Tafl.Side) = if (side == Tafl.Side.ATTACKERS) "attackers" else "defenders"

@Composable
private fun Board(state: TaflState, onTap: (Int, Int) -> Unit) {
    val last = state.lastMove
    val lastSquares = last?.let { setOf(it.fromX to it.fromY, it.toX to it.toY) } ?: emptySet()
    val captured = state.captured.toSet()
    val size = state.size
    Column(
        modifier = Modifier.fillMaxWidth().aspectRatio(1f).border(2.dp, SpecialSquare, RoundedCornerShape(4.dp)).padding(2.dp),
    ) {
        for (y in 0 until size) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                for (x in 0 until size) {
                    // Corners are the king's goal in corner variants; elsewhere they are just
                    // restricted squares. The throne is marked in every variant.
                    val special = Tafl.isCorner(size, x, y) || Tafl.isThrone(size, x, y)
                    val base = if (special) SpecialSquare else if ((x + y) % 2 == 0) Square else SquareAlt
                    val sq = x to y
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .background(base)
                            .border(0.5.dp, GridLine)
                            .then(if (sq in lastSquares) Modifier.background(LastMoveTint) else Modifier)
                            .then(if (sq in captured) Modifier.background(CapturedTint) else Modifier)
                            .then(if (state.selected == sq) Modifier.background(SelectedTint) else Modifier)
                            .clickable { onTap(x, y) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (sq in state.targets) {
                            Box(modifier = Modifier.fillMaxSize(0.3f).background(Color(0x66000000), CircleShape))
                        }
                        val piece = state.squares[y * size + x]
                        if (piece != Tafl.EMPTY) {
                            val res = when (piece) {
                                Tafl.ATTACKER -> R.drawable.tafl_attacker
                                Tafl.DEFENDER -> R.drawable.tafl_defender
                                else -> R.drawable.tafl_king
                            }
                            Image(painterResource(res), contentDescription = null, modifier = Modifier.fillMaxSize(0.92f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClockRow(name: String, side: Tafl.Side, ms: Long?, startingMs: Long?, active: Boolean, lost: Boolean) {
    val warnMs = minOf(10_000L, (startingMs ?: 0L) / 5)
    val bg = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth().background(bg, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Image(
                painterResource(if (side == Tafl.Side.ATTACKERS) R.drawable.tafl_attacker else R.drawable.tafl_king),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
            )
            Text(name, style = MaterialTheme.typography.bodyLarge)
        }
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
private fun GameOverBanner(state: TaflState, onRematch: () -> Unit, onNewGame: () -> Unit) {
    val (title, color) = when (state.result) {
        TaflResult.PLAYER_WINS -> "You won" to MaterialTheme.colorScheme.primaryContainer
        TaflResult.ENGINE_WINS -> "${state.config.engine.label} won" to MaterialTheme.colorScheme.errorContainer
        else -> "Draw" to MaterialTheme.colorScheme.surfaceVariant
    }
    Column(
        modifier = Modifier.fillMaxWidth().background(color, RoundedCornerShape(12.dp)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text("Rematch swaps sides.", style = MaterialTheme.typography.bodySmall)
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
        items(rows) { row ->
            Text(row, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(vertical = 2.dp))
        }
    }
}
