package io.github.usernamealreadytakensht.games.ui.fox

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.geometry.Size
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
import io.github.usernamealreadytakensht.games.game.fox.Fox
import io.github.usernamealreadytakensht.games.game.fox.FoxConfig
import io.github.usernamealreadytakensht.games.game.fox.FoxResult
import io.github.usernamealreadytakensht.games.game.fox.FoxState
import io.github.usernamealreadytakensht.games.game.fox.FoxVariant
import io.github.usernamealreadytakensht.games.game.fox.FoxViewModel
import kotlin.math.hypot

private val BoardColor = Color(0xFFEAD9C0)
private val DarkSquare = Color(0xFF8B6B4A)
private val LineColor = Color(0xFF6B4F35)
private val SelectedRing = Color(0xFF20A0FF)
private val LastMoveRing = Color(0xFFE0B020)
private val TargetDot = Color(0x66000000)
private val FortressTint = Color(0x33C05000)

/** How the game screen is entered: a fresh game with [config], or the saved one. */
sealed interface FoxLaunch {
    data class NewGame(val config: FoxConfig, val id: Int) : FoxLaunch
    data object Resume : FoxLaunch
}

@Composable
fun FoxScreen(
    launch: FoxLaunch,
    onBack: () -> Unit,
    onNewGame: () -> Unit,
    vm: FoxViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showResign by remember { mutableStateOf(false) }

    LaunchedEffect(launch) {
        when (launch) {
            is FoxLaunch.NewGame -> vm.startGame(launch.config)
            FoxLaunch.Resume -> if (!vm.resumeGame()) onBack()
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

    val v = state.variant
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
                    Text(v.label, style = MaterialTheme.typography.titleLarge)
                }
                Text(state.config.timeControl.label, style = MaterialTheme.typography.labelLarge)
            }

            ClockRow(
                "${state.config.opponentLabel} · ${sideName(v, state.engineSide)}", v, state.engineSide,
                state.clockMs(state.engineSide), state.config.timeControl.startingMs,
                active = state.runningClock == state.engineSide, lost = state.result == FoxResult.PLAYER_WINS,
            )

            if (state.points.isNotEmpty()) {
                if (v == FoxVariant.HOUNDS) Checkerboard(state, onTap = vm::onPointTapped)
                else CrossBoard(state, onTap = vm::onPointTapped)
            }

            ClockRow(
                "You · ${sideName(v, state.playerSide)}", v, state.playerSide,
                state.clockMs(state.playerSide), state.config.timeControl.startingMs,
                active = state.runningClock == state.playerSide, lost = state.result == FoxResult.ENGINE_WINS,
            )

            Row(modifier = Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.thinking) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(state.statusText, style = MaterialTheme.typography.bodyLarge)
            }

            if (state.result != FoxResult.ONGOING) {
                GameOverBanner(state, onRematch = vm::rematch, onNewGame = onNewGame)
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::undo, enabled = state.canUndo, modifier = Modifier.weight(1f)) {
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

private fun sideName(v: FoxVariant, side: Fox.Side) = if (side == Fox.Side.FOX) v.foxName else v.hunterName

private fun pieceRes(v: FoxVariant, piece: Int): Int = when (piece) {
    Fox.FOX -> R.drawable.fox_fox
    else -> if (v == FoxVariant.HOUNDS) R.drawable.fox_hound else R.drawable.fox_goose
}

/**
 * Shared board body: squares or lines are drawn by [background], then rings, targets and
 * pieces at the variant's point positions. The board is [cells] cells wide and tall.
 */
@Composable
private fun PointBoard(
    state: FoxState,
    cells: Int,
    margin: Float,
    onTap: (Int) -> Unit,
    background: androidx.compose.ui.graphics.drawscope.DrawScope.(cell: Float, origin: Float) -> Unit,
) {
    val v = state.variant
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(BoardColor, RoundedCornerShape(12.dp))) {
        val side = maxWidth
        val cellDp = (side * (1f - 2 * margin)) / cells
        val originDp = side * margin
        val pieceSize = cellDp * 0.82f
        val b = v.board
        fun cx(p: Int) = originDp + cellDp * (b.col(p) + 0.5f)
        fun cy(p: Int) = originDp + cellDp * (b.row(p) + 0.5f)

        Canvas(
            modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures { tap ->
                    val cellPx = cellDp.toPx()
                    var best = -1
                    var bestDist = cellPx * 0.5f
                    for (p in 0 until b.points) {
                        val d = hypot(tap.x - cx(p).toPx(), tap.y - cy(p).toPx())
                        if (d < bestDist) { best = p; bestDist = d }
                    }
                    if (best >= 0) onTap(best)
                }
            },
        ) {
            background(cellDp.toPx(), originDp.toPx())
            val ring = pieceSize.toPx() / 2 + 2.dp.toPx()
            val ringStroke = Stroke(width = 3.dp.toPx())
            state.lastMove?.let { m ->
                for (p in listOf(m.from, m.to)) {
                    drawCircle(LastMoveRing, radius = ring, center = Offset(cx(p).toPx(), cy(p).toPx()), style = ringStroke)
                }
            }
            state.selected?.let { p ->
                drawCircle(SelectedRing, radius = ring, center = Offset(cx(p).toPx(), cy(p).toPx()), style = ringStroke)
            }
            for (p in state.targets) {
                drawCircle(TargetDot, radius = pieceSize.toPx() * 0.18f, center = Offset(cx(p).toPx(), cy(p).toPx()))
            }
        }

        for (p in 0 until b.points) {
            val piece = state.points[p]
            if (piece == Fox.EMPTY) continue
            Image(
                painterResource(pieceRes(v, piece)),
                contentDescription = null,
                modifier = Modifier.offset(x = cx(p) - pieceSize / 2, y = cy(p) - pieceSize / 2).size(pieceSize),
            )
        }
    }
}

/** Fox and Hounds: the 8x8 checkerboard, pieces on the dark squares. */
@Composable
private fun Checkerboard(state: FoxState, onTap: (Int) -> Unit) {
    PointBoard(state, cells = 8, margin = 0.03f, onTap = onTap) { cell, origin ->
        for (r in 0 until 8) for (c in 0 until 8) {
            if ((r + c) % 2 == 1) {
                drawRect(DarkSquare, topLeft = Offset(origin + c * cell, origin + r * cell), size = Size(cell, cell))
            }
        }
    }
}

/** The cross board (Fox and Geese, Asalto) with its orthogonal and diagonal lines. */
@Composable
private fun CrossBoard(state: FoxState, onTap: (Int) -> Unit) {
    val v = state.variant
    val b = v.board
    val fortress = v.fortress.toSet()
    PointBoard(state, cells = 7, margin = 0.05f, onTap = onTap) { cell, origin ->
        val stroke = 2.dp.toPx()
        fun at(p: Int) = Offset(origin + cell * (b.col(p) + 0.5f), origin + cell * (b.row(p) + 0.5f))
        // Asalto: shade the fortress the sepoys have to fill.
        if (fortress.isNotEmpty()) {
            val minCol = fortress.minOf { b.col(it) }
            val minRow = fortress.minOf { b.row(it) }
            val cols = fortress.map { b.col(it) }.distinct().size
            val rows = fortress.map { b.row(it) }.distinct().size
            drawRect(
                FortressTint,
                topLeft = Offset(origin + cell * minCol, origin + cell * minRow),
                size = Size(cell * cols, cell * rows),
            )
        }
        // Draw each line once, from a point to its right / lower / diagonal neighbours.
        for (p in 0 until b.points) {
            for (d in b.directions.indices) {
                val (dx, dy) = b.directions[d]
                if (dy < 0 || (dy == 0 && dx < 0)) continue
                val n = b.step(p, d)
                if (n >= 0) drawLine(LineColor, at(p), at(n), strokeWidth = stroke)
            }
        }
        for (p in 0 until b.points) drawCircle(LineColor, radius = 4.dp.toPx(), center = at(p))
    }
}

@Composable
private fun ClockRow(name: String, v: FoxVariant, side: Fox.Side, ms: Long?, startingMs: Long?, active: Boolean, lost: Boolean) {
    val warnMs = minOf(10_000L, (startingMs ?: 0L) / 5)
    val bg = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth().background(bg, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Image(
                painterResource(pieceRes(v, if (side == Fox.Side.FOX) Fox.FOX else Fox.HUNTER)),
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
private fun GameOverBanner(state: FoxState, onRematch: () -> Unit, onNewGame: () -> Unit) {
    val (title, color) = when (state.result) {
        FoxResult.PLAYER_WINS -> "You won" to MaterialTheme.colorScheme.primaryContainer
        else -> "${state.config.engine.label} won" to MaterialTheme.colorScheme.errorContainer
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
