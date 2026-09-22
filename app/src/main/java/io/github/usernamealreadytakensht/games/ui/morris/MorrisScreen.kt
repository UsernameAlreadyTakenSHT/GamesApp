package io.github.usernamealreadytakensht.games.ui.morris

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
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
import io.github.usernamealreadytakensht.games.game.morris.Morris
import io.github.usernamealreadytakensht.games.game.morris.MorrisConfig
import io.github.usernamealreadytakensht.games.game.morris.MorrisResult
import io.github.usernamealreadytakensht.games.game.morris.MorrisState
import io.github.usernamealreadytakensht.games.game.morris.MorrisViewModel
import kotlin.math.hypot

private val BoardColor = Color(0xFFEAD9C0)
private val LineColor = Color(0xFF6B4F35)
private val SelectedRing = Color(0xFF20A0FF)
private val LastMoveRing = Color(0xFFE0B020)
private val RemovableRing = Color(0xFFE04040)
private val TargetDot = Color(0x66000000)

/** How the game screen is entered: a fresh game with [config], or the saved one. */
sealed interface MorrisLaunch {
    data class NewGame(val config: MorrisConfig, val id: Int) : MorrisLaunch
    data object Resume : MorrisLaunch
}

@Composable
fun MorrisScreen(
    launch: MorrisLaunch,
    onBack: () -> Unit,
    onNewGame: () -> Unit,
    vm: MorrisViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showResign by remember { mutableStateOf(false) }

    LaunchedEffect(launch) {
        when (launch) {
            is MorrisLaunch.NewGame -> vm.startGame(launch.config)
            MorrisLaunch.Resume -> if (!vm.resumeGame()) onBack()
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
                    Text("Nine Men's Morris", style = MaterialTheme.typography.titleLarge)
                }
                Text(state.config.timeControl.label, style = MaterialTheme.typography.labelLarge)
            }

            ClockRow(
                state.config.opponentLabel, state.inHand(state.engineSide), state.engineSide,
                state.clockMs(state.engineSide), state.config.timeControl.startingMs,
                active = state.runningClock == state.engineSide, lost = state.result == MorrisResult.PLAYER_WINS,
            )

            Board(state, onTap = vm::onPointTapped)

            ClockRow(
                "You", state.inHand(state.playerSide), state.playerSide,
                state.clockMs(state.playerSide), state.config.timeControl.startingMs,
                active = state.runningClock == state.playerSide, lost = state.result == MorrisResult.ENGINE_WINS,
            )

            Row(modifier = Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.thinking) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(state.statusText, style = MaterialTheme.typography.bodyLarge,
                    color = if (state.engineError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            }

            if (state.result != MorrisResult.ONGOING) {
                GameOverBanner(state, onRematch = vm::rematch, onNewGame = onNewGame)
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::undo, enabled = state.canUndo && state.engineError == null, modifier = Modifier.weight(1f)) {
                        Text("Undo")
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

/** The three squares with their connecting lines; men are images laid over the canvas. */
@Composable
private fun Board(state: MorrisState, onTap: (Int) -> Unit) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(BoardColor, RoundedCornerShape(12.dp)),
    ) {
        val side = maxWidth
        val margin = side * 0.09f
        val step = (side - margin * 2) / 6
        val manRadius = step * 0.4f
        fun cx(p: Int) = margin + step * Morris.POINT_COL[p]
        fun cy(p: Int) = margin + step * Morris.POINT_ROW[p]

        Canvas(
            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures { tap ->
                    val stepPx = step.toPx()
                    var best = -1
                    var bestDist = stepPx * 0.5f
                    for (p in 0 until 24) {
                        val d = hypot(tap.x - cx(p).toPx(), tap.y - cy(p).toPx())
                        if (d < bestDist) { best = p; bestDist = d }
                    }
                    if (best >= 0) onTap(best)
                }
            },
        ) {
            val stroke = 2.dp.toPx()
            for (line in Morris.LINES) {
                drawLine(
                    LineColor,
                    Offset(cx(line[0]).toPx(), cy(line[0]).toPx()),
                    Offset(cx(line[2]).toPx(), cy(line[2]).toPx()),
                    strokeWidth = stroke,
                )
            }
            for (p in 0 until 24) drawCircle(LineColor, radius = 4.dp.toPx(), center = Offset(cx(p).toPx(), cy(p).toPx()))

            val ring = manRadius.toPx() + 3.dp.toPx()
            val ringStroke = Stroke(width = 3.dp.toPx())
            state.lastMove?.let { m ->
                for (p in listOf(m.from, m.to)) if (p >= 0) {
                    drawCircle(LastMoveRing, radius = ring, center = Offset(cx(p).toPx(), cy(p).toPx()), style = ringStroke)
                }
            }
            state.selected?.let { p ->
                drawCircle(SelectedRing, radius = ring, center = Offset(cx(p).toPx(), cy(p).toPx()), style = ringStroke)
            }
            for (p in state.removable) {
                drawCircle(RemovableRing, radius = ring, center = Offset(cx(p).toPx(), cy(p).toPx()), style = ringStroke)
            }
            for (p in state.targets) {
                drawCircle(TargetDot, radius = manRadius.toPx() * 0.4f, center = Offset(cx(p).toPx(), cy(p).toPx()))
            }
        }

        val pending = state.pendingMove
        for (p in 0 until 24) {
            var stone = state.points[p]
            var alpha = 1f
            // The half-played move: its man shows as a ghost on the destination, and has left its origin.
            if (pending != null) {
                if (p == pending.from) continue
                if (p == pending.to) { stone = Morris.stone(state.playerSide); alpha = 0.55f }
            }
            if (stone == Morris.EMPTY) continue
            Image(
                painterResource(if (stone == Morris.WHITE) R.drawable.stone_l1 else R.drawable.stone_d1),
                contentDescription = null,
                alpha = alpha,
                modifier = Modifier
                    .offset(x = cx(p) - manRadius, y = cy(p) - manRadius)
                    .size(manRadius * 2),
            )
        }
    }
}

@Composable
private fun ClockRow(name: String, inHand: Int, side: Morris.Color, ms: Long?, startingMs: Long?, active: Boolean, lost: Boolean) {
    val warnMs = minOf(10_000L, (startingMs ?: 0L) / 5)
    val bg = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth().background(bg, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            // Men still to be placed, shown as a short stack of stones.
            if (inHand > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy((-10).dp)) {
                    repeat(inHand) {
                        Image(
                            painterResource(if (side == Morris.Color.WHITE) R.drawable.stone_l1 else R.drawable.stone_d1),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
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
private fun GameOverBanner(state: MorrisState, onRematch: () -> Unit, onNewGame: () -> Unit) {
    val (title, color) = when (state.result) {
        MorrisResult.PLAYER_WINS -> "You won" to MaterialTheme.colorScheme.primaryContainer
        MorrisResult.ENGINE_WINS -> "${state.config.engine.family.label} won" to MaterialTheme.colorScheme.errorContainer
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
        items(rows) { row ->
            Text(row, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(vertical = 2.dp))
        }
    }
}
