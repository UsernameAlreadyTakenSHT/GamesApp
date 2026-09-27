package io.github.usernamealreadytakensht.games.ui.shogi

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.usernamealreadytakensht.games.R
import io.github.usernamealreadytakensht.games.game.shogi.Shogi
import io.github.usernamealreadytakensht.games.game.shogi.ShogiConfig
import io.github.usernamealreadytakensht.games.game.shogi.ShogiLevels
import io.github.usernamealreadytakensht.games.game.shogi.ShogiPieceStyle
import io.github.usernamealreadytakensht.games.ui.ClockSection
import io.github.usernamealreadytakensht.games.ui.EngineBadge
import io.github.usernamealreadytakensht.games.ui.Hint
import io.github.usernamealreadytakensht.games.ui.OpponentCard
import io.github.usernamealreadytakensht.games.ui.SectionTitle
import io.github.usernamealreadytakensht.games.ui.SelectableCard
import io.github.usernamealreadytakensht.games.ui.SetupScaffold
import io.github.usernamealreadytakensht.games.ui.SideCards
import io.github.usernamealreadytakensht.games.ui.StrengthCard

// ---------------------------------------------------------------- screen 1: game

/** First setup screen: side, piece style and clock. [config] is owned by the caller. */
@Composable
fun ShogiGameSetupScreen(
    config: ShogiConfig,
    onChange: (ShogiConfig) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    SetupScaffold(title = "New game", step = "1 / 2", onBack = onBack, action = "Next", onAction = onNext) {
        SectionTitle("Your side")
        SideCards(
            first = "Sente" to listOf(R.drawable.shogi_sente),
            second = "Gote" to listOf(R.drawable.shogi_gote),
            selected = config.playerSide?.let { it == Shogi.Side.SENTE },
            onSelect = { onChange(config.copy(playerSide = it?.let { s -> if (s) Shogi.Side.SENTE else Shogi.Side.GOTE })) },
        )
        Hint("Sente moves first. Captured pieces change sides and can be dropped back on the board.")

        SectionTitle("Pieces")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(IntrinsicSize.Max)) {
            ShogiPieceStyle.entries.forEach { style ->
                SelectableCard(config.pieces == style, Modifier.weight(1f).fillMaxHeight(), { onChange(config.copy(pieces = style)) }) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            for (kind in listOf(Shogi.Kind.KING, Shogi.Kind.ROOK, Shogi.Kind.PAWN)) {
                                Box(Modifier.size(34.dp)) {
                                    ShogiPiece(kind, false, Shogi.Side.SENTE, upright = true, style, 34.dp)
                                }
                            }
                        }
                        Text(style.label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                        Text(
                            style.tagline,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }

        ClockSection(config.timeControl, byoyomi = true) { onChange(config.copy(timeControl = it)) }
    }
}

// ---------------------------------------------------------------- screen 2: opponent

/** Second setup screen: the engine and its level. */
@Composable
fun ShogiOpponentSetupScreen(
    config: ShogiConfig,
    onChange: (ShogiConfig) -> Unit,
    onBack: () -> Unit,
    onPlay: () -> Unit,
) {
    val presets = ShogiLevels.PRESETS
    val idx = presets.indexOf(config.level).coerceAtLeast(0)
    SetupScaffold(
        title = "Opponent",
        step = "2 / 2",
        onBack = onBack,
        action = "Play",
        onAction = onPlay,
        summary = "${config.playerSide?.let { if (it == Shogi.Side.SENTE) "Sente" else "Gote" } ?: "Random side"} · " +
            "${config.timeControl.label} · vs ${config.opponentLabel}",
    ) {
        SectionTitle("Engine")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(IntrinsicSize.Max)) {
            OpponentCard(
                label = "Fairy-Stockfish",
                tagline = "Stockfish for chess variants.",
                selected = true,
                modifier = Modifier.weight(1f).fillMaxHeight(),
                onClick = {},
            ) { EngineBadge(logo = null, monogram = "FS", hue = 190f, icon = R.drawable.ic_engine_fairy) }
            Spacer(Modifier.weight(1f))
        }
        Hint("Stockfish's search adapted to shogi (drops, promotions), with a shogi NNUE network.")

        SectionTitle("Strength")
        StrengthCard(
            big = config.level?.toString() ?: "Max",
            unit = if (config.level == null) "full strength" else "level",
            description = ShogiLevels.description(config.level),
            presets = presets.size,
            index = idx,
            rangeStart = "Level 1",
            rangeEnd = "Max",
            onIndex = { onChange(config.copy(level = presets[it])) },
        )
    }
}
