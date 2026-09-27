package io.github.usernamealreadytakensht.games.ui.shogi

import io.github.usernamealreadytakensht.games.ui.ShareGameButton
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.usernamealreadytakensht.games.game.TimeControl
import io.github.usernamealreadytakensht.games.game.shogi.Shogi
import io.github.usernamealreadytakensht.games.game.shogi.Shogi.Kind
import io.github.usernamealreadytakensht.games.game.shogi.Shogi.Side
import io.github.usernamealreadytakensht.games.game.shogi.ShogiConfig
import io.github.usernamealreadytakensht.games.game.shogi.ShogiPieceStyle
import io.github.usernamealreadytakensht.games.game.shogi.ShogiResult
import io.github.usernamealreadytakensht.games.game.shogi.ShogiState
import io.github.usernamealreadytakensht.games.game.shogi.ShogiViewModel

private val Wood = Color(0xFFE9C987)
private val WoodDark = Color(0xFF9C7A45)
private val GridLine = Color(0xFF5C4424)
private val SelectedTint = Color(0x6620A0FF)
private val LastMoveTint = Color(0x66F5B942)
private val CheckTint = Color(0x88E53935)
private val HandBg = Color(0xFFD9B878)

/** How the game screen is entered: a fresh game with [config], or the saved one. */
sealed interface ShogiLaunch {
    data class NewGame(val config: ShogiConfig, val id: Int) : ShogiLaunch
    data object Resume : ShogiLaunch
}

@Composable
fun ShogiScreen(
    launch: ShogiLaunch,
    onBack: () -> Unit,
    onNewGame: () -> Unit,
    vm: ShogiViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showResign by remember { mutableStateOf(false) }

    LaunchedEffect(launch) {
        when (launch) {
            is ShogiLaunch.NewGame -> vm.startGame(launch.config)
            ShogiLaunch.Resume -> if (!vm.resumeGame()) onBack()
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
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); vm.pauseClock(); vm.releaseEngine() }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
        Column(
            modifier = Modifier.fillMaxSize().padding(inner).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) }
                    Text("Shogi", style = MaterialTheme.typography.titleLarge)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(state.config.timeControl.label, style = MaterialTheme.typography.labelLarge)
                    ShareGameButton(vm::exportGame)
                }
            }

            ClockRow(
                "${state.config.opponentLabel} · ${sideName(state.engineSide)}",
                state.clockMs(state.engineSide), state.byoyomiMs(state.engineSide), state.config.timeControl,
                active = state.runningClock == state.engineSide, lost = state.result == ShogiResult.PLAYER_WINS,
            )

            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                // Nine cells plus a coordinate margin on the right.
                val cell = (maxWidth - 16.dp) / 9
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Hand(state, state.engineSide, cell, onTap = {})
                    Board(state, cell, onTap = vm::onSquareTapped)
                    Hand(state, state.playerSide, cell, onTap = vm::onHandTapped)
                }
            }

            ClockRow(
                "You · ${sideName(state.playerSide)}",
                state.clockMs(state.playerSide), state.byoyomiMs(state.playerSide), state.config.timeControl,
                active = state.runningClock == state.playerSide, lost = state.result == ShogiResult.ENGINE_WINS,
            )

            Row(
                modifier = Modifier.fillMaxWidth().height(36.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (state.thinking) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    state.statusText,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (state.engineError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (state.engineError != null && state.result == ShogiResult.ONGOING) {
                    TextButton(onClick = vm::retryEngine) { Text("Retry") }
                }
            }

            if (state.result != ShogiResult.ONGOING) {
                GameOverBanner(state, onRematch = vm::rematch, onNewGame = onNewGame)
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = vm::undo, enabled = state.canUndo, modifier = Modifier.weight(1f)) { Text("Undo") }
                    OutlinedButton(onClick = { showResign = true }, modifier = Modifier.weight(1f)) { Text("Resign") }
                }
            }

            MoveList(state.moves)
        }
    }

    state.pendingPromotion?.let { move ->
        val piece = state.pieces[move.from]
        if (piece != null) {
            AlertDialog(
                onDismissRequest = vm::cancelPromotion,
                title = { Text("Promote?") },
                text = {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                        for (promote in listOf(true, false)) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .weight(1f)
                                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                                    .clickable { vm.choosePromotion(promote) }
                                    .padding(12.dp),
                            ) {
                                Box(Modifier.size(56.dp)) {
                                    ShogiPiece(piece.kind, promote, piece.side, upright = true, state.config.pieces, 56.dp)
                                }
                                Text(if (promote) "Promote" else "Keep", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = { TextButton(onClick = vm::cancelPromotion) { Text("Cancel") } },
            )
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

private fun sideName(side: Side) = if (side == Side.SENTE) "sente" else "gote"

/** The player's side is always at the bottom: the board turns round when playing gote. */
@Composable
private fun Board(state: ShogiState, cell: Dp, onTap: (Int) -> Unit) {
    val flipped = state.playerSide == Side.GOTE
    val last = state.lastMove
    val lastSquares = last?.let { setOfNotNull(it.to, it.from.takeIf { f -> f >= 0 }) } ?: emptySet()
    Column {
        // File numbers, 9 → 1 from Sente's left.
        Row {
            for (col in 0 until 9) {
                val file = if (flipped) 8 - col else col
                Text(
                    "${9 - file}",
                    modifier = Modifier.width(cell),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        Row {
            Column(modifier = Modifier.background(Wood).border(1.5.dp, GridLine)) {
                for (row in 0 until 9) {
                    Row {
                        for (col in 0 until 9) {
                            val rank = if (flipped) row else 8 - row
                            val file = if (flipped) 8 - col else col
                            val sq = rank * 9 + file
                            val tint = when {
                                sq == state.selected -> SelectedTint
                                sq == state.checkedKing -> CheckTint
                                sq in lastSquares -> LastMoveTint
                                else -> Color.Transparent
                            }
                            Box(
                                modifier = Modifier
                                    .size(cell)
                                    .border(0.5.dp, GridLine.copy(alpha = 0.7f))
                                    .background(tint)
                                    .clickable { onTap(sq) },
                                contentAlignment = Alignment.Center,
                            ) {
                                val piece = state.pieces[sq]
                                if (piece != null) {
                                    ShogiPiece(piece.kind, piece.promoted, piece.side, piece.side == state.playerSide, state.config.pieces, cell)
                                }
                                if (sq in state.targets) {
                                    val dot = if (piece != null) 0.9f else 0.28f
                                    Box(
                                        Modifier
                                            .fillMaxSize(dot)
                                            .then(
                                                if (piece != null) Modifier.border(3.dp, Color(0x88000000), CircleShape)
                                                else Modifier.background(Color(0x55000000), CircleShape),
                                            ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // Rank letters, a → i from Gote's side.
            Column {
                for (row in 0 until 9) {
                    val rank = if (flipped) row else 8 - row
                    Box(Modifier.size(width = 16.dp, height = cell), contentAlignment = Alignment.Center) {
                        Text(
                            "${'a' + (8 - rank)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** Pieces in [side]'s hand, most valuable first; the player's can be picked for a drop. */
@Composable
private fun Hand(state: ShogiState, side: Side, cell: Dp, onTap: (Kind) -> Unit) {
    val counts = state.hand(side)
    val kinds = Kind.HAND.reversed().filter { counts[Kind.HAND.indexOf(it)] > 0 }
    val mine = side == state.playerSide
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(cell + 8.dp)
            .background(HandBg.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
            .border(1.dp, WoodDark.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (kinds.isEmpty()) {
            Text(
                if (mine) "Your captured pieces" else "Captured by ${state.config.opponentLabel.substringBefore(" ·")}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (kind in kinds) {
            val n = counts[Kind.HAND.indexOf(kind)]
            Box(
                modifier = Modifier
                    .size(cell)
                    .background(if (mine && state.selectedDrop == kind) SelectedTint else Color.Transparent, RoundedCornerShape(6.dp))
                    .then(if (mine) Modifier.clickable { onTap(kind) } else Modifier),
            ) {
                ShogiPiece(kind, false, side, upright = mine, state.config.pieces, cell)
                if (n > 1) {
                    Text(
                        "$n",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .background(WoodDark, CircleShape)
                            .padding(horizontal = 5.dp),
                    )
                }
            }
        }
    }
}

/**
 * A player's bar: name and clock. With byoyomi the main time shows with its period ("+30s")
 * until it runs out, then the byoyomi countdown takes over.
 */
@Composable
private fun ClockRow(name: String, ms: Long?, byoyomiMs: Long?, clock: TimeControl, active: Boolean, lost: Boolean) {
    val warnMs = minOf(10_000L, (clock.startingMs ?: 0L) / 5)
    val bg = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth().background(bg, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = MaterialTheme.typography.bodyLarge)
        if (ms != null) {
            val inByoyomi = byoyomiMs != null && ms <= 0L
            val shown = if (inByoyomi) byoyomiMs!! else ms
            // Byoyomi turns red for the last 10 s, or the last half of a shorter period.
            val period = (clock as? TimeControl.Byoyomi)?.byoyomiMs ?: 0L
            val warn = if (byoyomiMs != null) inByoyomi && shown < minOf(10_000L, period / 2) else ms < warnMs
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (byoyomiMs != null) {
                    Text(
                        if (inByoyomi) "byoyomi" else "+${byoyomiMs / 1000}s",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
                Text(
                    formatClock(shown),
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (lost || warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
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
private fun GameOverBanner(state: ShogiState, onRematch: () -> Unit, onNewGame: () -> Unit) {
    val (title, color) = when (state.result) {
        ShogiResult.PLAYER_WINS -> "You won" to MaterialTheme.colorScheme.primaryContainer
        ShogiResult.ENGINE_WINS -> "Fairy-Stockfish won" to MaterialTheme.colorScheme.errorContainer
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

/** Numbered moves on one scrolling line, so the board keeps the room. */
@Composable
private fun MoveList(moves: List<String>) {
    val listState = rememberLazyListState()
    LaunchedEffect(moves.size) { if (moves.isNotEmpty()) listState.animateScrollToItem(moves.size - 1) }
    LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        itemsIndexed(moves) { i, m ->
            Text(
                "${i + 1}. $m",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}
