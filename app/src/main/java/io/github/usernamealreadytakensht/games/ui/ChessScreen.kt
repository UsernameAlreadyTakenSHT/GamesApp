package io.github.usernamealreadytakensht.games.ui

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
import io.github.usernamealreadytakensht.games.game.Takebacks
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
import io.github.usernamealreadytakensht.games.game.ChessViewModel
import io.github.usernamealreadytakensht.games.game.GameConfig
import io.github.usernamealreadytakensht.games.game.GameState
import io.github.usernamealreadytakensht.games.game.Result
import com.github.bhlangonijr.chesslib.Piece
import com.github.bhlangonijr.chesslib.PieceType
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.Square

private val LightSquare = Color(0xFFF0D9B5)
private val DarkSquare = Color(0xFFB58863)
private val SelectedTint = Color(0x8020A0FF)
private val LastMoveTint = Color(0x60F5D742)
private val CheckTint = Color(0x90E53935)

/** How the game screen is entered: a fresh game with [config], or the saved one. */
sealed interface ChessLaunch {
    data class NewGame(val config: GameConfig, val id: Int) : ChessLaunch
    data object Resume : ChessLaunch
}

/** Game screen. */
@Composable
fun ChessScreen(
    launch: ChessLaunch,
    onBack: () -> Unit,
    onNewGame: () -> Unit,
    vm: ChessViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showResign by remember { mutableStateOf(false) }

    LaunchedEffect(launch) {
        when (launch) {
            is ChessLaunch.NewGame -> vm.startGame(launch.config)
            ChessLaunch.Resume -> if (!vm.resumeGame()) onBack()
        }
    }

    // Freeze the clock while the app is in the background or this screen is left.
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
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            vm.pauseClock()
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) }
                    Text("Chess", style = MaterialTheme.typography.titleLarge)
                }
                Text(state.config.timeControl.label, style = MaterialTheme.typography.labelLarge)
            }

            ClockRow(
                name = state.config.opponentLabel,
                ms = state.clockMs(state.engineSide),
                startingMs = state.config.timeControl.startingMs,
                active = state.runningClock == state.engineSide,
                lost = state.result == Result.PLAYER_WINS,
            )

            Board(state, onTap = vm::onSquareTapped)

            ClockRow(
                name = "You",
                ms = state.clockMs(state.playerSide),
                startingMs = state.config.timeControl.startingMs,
                active = state.runningClock == state.playerSide,
                lost = state.result == Result.ENGINE_WINS,
            )

            StatusLine(state)

            if (state.result != Result.ONGOING) {
                GameOverBanner(state, onRematch = vm::rematch, onNewGame = onNewGame)
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.config.takebacks != Takebacks.OFF) {
                        OutlinedButton(
                            onClick = vm::undo,
                            enabled = state.canUndo && state.engineError == null,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(state.takebacksLeft?.let { "Undo ($it)" } ?: "Undo")
                        }
                    }
                    OutlinedButton(onClick = vm::flipBoard, modifier = Modifier.weight(1f)) { Text("Flip") }
                    OutlinedButton(
                        onClick = { showResign = true },
                        enabled = state.sanMoves.isNotEmpty() || state.playerSide == Side.WHITE,
                        modifier = Modifier.weight(1f),
                    ) { Text("Resign") }
                }
            }

            MoveList(state.sanMoves, modifier = Modifier.weight(1f))
        }
    }

    state.pendingPromotion?.let {
        PromotionDialog(state.playerSide, onPick = vm::promote, onDismiss = vm::cancelPromotion)
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

/** Result banner with the two ways to continue. */
@Composable
private fun GameOverBanner(state: GameState, onRematch: () -> Unit, onNewGame: () -> Unit) {
    val (title, color) = when (state.result) {
        Result.PLAYER_WINS -> "You won" to MaterialTheme.colorScheme.primaryContainer
        Result.ENGINE_WINS -> "${state.config.engine.label} won" to MaterialTheme.colorScheme.errorContainer
        else -> "Draw" to MaterialTheme.colorScheme.surfaceVariant
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(color, RoundedCornerShape(12.dp))
            .padding(12.dp),
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

/** Name + clock of one side. Without a clock only the name is shown. */
@Composable
private fun ClockRow(name: String, ms: Long?, startingMs: Long?, active: Boolean, lost: Boolean) {
    // Warning threshold: 10 s, or 20 % of the starting time for short controls.
    val warnMs = minOf(10_000L, (startingMs ?: 0L) / 5)
    val bg = when {
        active -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
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
                color = when {
                    lost || ms < warnMs -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

private fun formatClock(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return if (ms < 10_000) {
        val tenths = (ms % 1000) / 100
        "%d:%02d.%d".format(m, s, tenths)
    } else {
        "%d:%02d".format(m, s)
    }
}

@Composable
private fun StatusLine(state: GameState) {
    Row(
        modifier = Modifier.fillMaxWidth().height(28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.thinking) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(
            state.statusText,
            style = MaterialTheme.typography.bodyLarge,
            color = if (state.engineError != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
        )
    }
}


@Composable
private fun Board(state: GameState, onTap: (Square) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f),
    ) {
        for (row in 0 until 8) {
            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                for (col in 0 until 8) {
                    // Orientation: rank 8 on top for white, rank 1 on top for black.
                    val rank = if (state.flipped) row else 7 - row
                    val file = if (state.flipped) 7 - col else col
                    val square = Square.squareAt(rank * 8 + file)
                    SquareCell(
                        square = square,
                        piece = state.pieces[square.ordinal],
                        isDark = (rank + file) % 2 == 0,
                        isSelected = state.selected == square,
                        isTarget = square in state.legalTargets,
                        isLastMove = state.lastMove?.let { it.first == square || it.second == square } == true,
                        isCheck = state.checkedKing == square,
                        showFileLabel = row == 7,
                        showRankLabel = col == 0,
                        modifier = Modifier.weight(1f).fillMaxSize(),
                        onTap = { onTap(square) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SquareCell(
    square: Square,
    piece: Piece,
    isDark: Boolean,
    isSelected: Boolean,
    isTarget: Boolean,
    isLastMove: Boolean,
    isCheck: Boolean,
    showFileLabel: Boolean,
    showRankLabel: Boolean,
    modifier: Modifier,
    onTap: () -> Unit,
) {
    val base = if (isDark) DarkSquare else LightSquare
    val labelColor = if (isDark) LightSquare else DarkSquare
    Box(
        modifier = modifier
            .background(base)
            .then(if (isLastMove) Modifier.background(LastMoveTint) else Modifier)
            .then(if (isSelected) Modifier.background(SelectedTint) else Modifier)
            .then(if (isCheck) Modifier.background(CheckTint) else Modifier)
            .clickable(onClick = onTap),
        contentAlignment = Alignment.Center,
    ) {
        if (showRankLabel) {
            Text(
                (square.rank.ordinal + 1).toString(),
                fontSize = 9.sp, color = labelColor, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 2.dp),
            )
        }
        if (showFileLabel) {
            Text(
                ('a' + square.file.ordinal).toString(),
                fontSize = 9.sp, color = labelColor, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 2.dp),
            )
        }
        if (isTarget) {
            if (piece == Piece.NONE) {
                Box(
                    modifier = Modifier
                        .fillMaxSize(0.3f)
                        .background(Color(0x55000000), CircleShape),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize(0.95f)
                        .background(Color(0x55000000), CircleShape),
                )
                Box(modifier = Modifier.fillMaxSize(0.8f).background(base, CircleShape))
            }
        }
        if (piece != Piece.NONE) PieceGlyph(piece)
    }
}

@Composable
private fun PieceGlyph(piece: Piece) {
    // "Cburnett" pieces (see art/pieces/README.md), converted to vector drawables.
    val id = when (piece) {
        Piece.WHITE_PAWN -> R.drawable.piece_lp
        Piece.WHITE_KNIGHT -> R.drawable.piece_ln
        Piece.WHITE_BISHOP -> R.drawable.piece_lb
        Piece.WHITE_ROOK -> R.drawable.piece_lr
        Piece.WHITE_QUEEN -> R.drawable.piece_lq
        Piece.WHITE_KING -> R.drawable.piece_lk
        Piece.BLACK_PAWN -> R.drawable.piece_dp
        Piece.BLACK_KNIGHT -> R.drawable.piece_dn
        Piece.BLACK_BISHOP -> R.drawable.piece_db
        Piece.BLACK_ROOK -> R.drawable.piece_dr
        Piece.BLACK_QUEEN -> R.drawable.piece_dq
        Piece.BLACK_KING -> R.drawable.piece_dk
        else -> return
    }
    Image(
        painter = painterResource(id),
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun MoveList(sanMoves: List<String>, modifier: Modifier = Modifier) {
    val rows = sanMoves.chunked(2).mapIndexed { i, pair ->
        "${i + 1}. ${pair[0]}" + (pair.getOrNull(1)?.let { "   $it" } ?: "")
    }
    val listState = rememberLazyListState()
    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty()) listState.animateScrollToItem(rows.size - 1)
    }
    LazyColumn(modifier = modifier.fillMaxWidth(), state = listState) {
        items(rows) { row ->
            Text(row, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp))
        }
    }
}

@Composable
private fun PromotionDialog(side: Side, onPick: (PieceType) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Promotion") },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT).forEach { type ->
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .background(DarkSquare)
                            .clickable { onPick(type) },
                        contentAlignment = Alignment.Center,
                    ) { PieceGlyph(Piece.make(side, type)) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
