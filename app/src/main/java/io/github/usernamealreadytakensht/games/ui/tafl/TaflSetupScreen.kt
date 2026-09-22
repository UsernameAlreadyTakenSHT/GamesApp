package io.github.usernamealreadytakensht.games.ui.tafl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.usernamealreadytakensht.games.R
import io.github.usernamealreadytakensht.games.game.tafl.Tafl
import io.github.usernamealreadytakensht.games.game.tafl.TaflConfig
import io.github.usernamealreadytakensht.games.game.tafl.TaflEngineKind
import io.github.usernamealreadytakensht.games.game.tafl.TaflVariant
import io.github.usernamealreadytakensht.games.ui.ClockSection
import io.github.usernamealreadytakensht.games.ui.EngineBadge
import io.github.usernamealreadytakensht.games.ui.Hint
import io.github.usernamealreadytakensht.games.ui.OpponentCard
import io.github.usernamealreadytakensht.games.ui.SectionTitle
import io.github.usernamealreadytakensht.games.ui.Segmented
import io.github.usernamealreadytakensht.games.ui.SelectableCard
import io.github.usernamealreadytakensht.games.ui.SetupScaffold
import io.github.usernamealreadytakensht.games.ui.SideCards
import io.github.usernamealreadytakensht.games.ui.StrengthCard

/*
 * The tafl games use the same two-step setup as the other games: variant, side, clock and
 * clock first, then the opponent and its search depth.
 */

// ---------------------------------------------------------------- screen 1: game

/** First setup screen: variant, side and clock. [config] is owned by the caller. */
@Composable
fun TaflGameSetupScreen(
    config: TaflConfig,
    onChange: (TaflConfig) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    SetupScaffold(title = "New game", step = "1 / 2", onBack = onBack, action = "Next", onAction = onNext) {
        SectionTitle("Variant")
        // Three cards per row: name, board size and a one-line hook.
        TaflVariant.entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(IntrinsicSize.Max)) {
                row.forEach { v ->
                    SelectableCard(config.variant == v, Modifier.weight(1f).fillMaxHeight(), { onChange(config.copy(variant = v)) }) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(v.label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center, maxLines = 1)
                            Text(
                                "${v.size}x${v.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                v.tagline,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Hint(config.variant.description)

        SectionTitle("Your side")
        SideCards(
            first = "Defenders" to listOf(R.drawable.tafl_king, R.drawable.tafl_defender),
            second = "Attackers" to listOf(R.drawable.tafl_attacker),
            selected = config.playerSide?.let { it == Tafl.Side.DEFENDERS },
            onSelect = { onChange(config.copy(playerSide = it?.let { d -> if (d) Tafl.Side.DEFENDERS else Tafl.Side.ATTACKERS })) },
        )
        Hint(
            "Attackers move first. The defenders win by walking the king " +
                (if (config.variant.escapeToCorners) "to a corner" else "over an edge") + "; the attackers by surrounding him.",
        )

        ClockSection(config.timeControl) { onChange(config.copy(timeControl = it)) }

    }
}

// ---------------------------------------------------------------- screen 2: opponent

private fun depthDescription(depth: Int?): String = when {
    depth == null -> "As strong as the thinking time allows"
    depth <= 2 -> "Beatable: overlooks captures"
    depth <= 4 -> "Fair game"
    depth <= 6 -> "Strong club player"
    else -> "Very hard to beat"
}

/** Second setup screen: opponent and search depth. */
@Composable
fun TaflOpponentSetupScreen(
    config: TaflConfig,
    onChange: (TaflConfig) -> Unit,
    onBack: () -> Unit,
    onPlay: () -> Unit,
) {
    val presets = TaflEngineKind.DEPTH_PRESETS
    val depthIdx = presets.indexOf(config.depth).coerceAtLeast(0)

    SetupScaffold(
        title = "Opponent",
        step = "2 / 2",
        onBack = onBack,
        action = "Play",
        onAction = onPlay,
        summary = "${config.variant.label} · " +
            "${config.playerSide?.let { if (it == Tafl.Side.DEFENDERS) "Defenders" else "Attackers" } ?: "Random side"} · " +
            "${config.timeControl.label} · vs ${config.opponentLabel}",
    ) {
        SectionTitle("Engine")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(IntrinsicSize.Max)) {
            TaflEngineKind.entries.forEach { e ->
                OpponentCard(
                    label = e.label,
                    tagline = "Rules and AI for every variant.",
                    selected = config.engine == e,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = { onChange(config.copy(engine = e)) },
                ) { EngineBadge(logo = null, monogram = "OT", hue = 200f, icon = R.drawable.ic_engine_opentafl) }
            }
            if (TaflEngineKind.entries.size == 1) Spacer(Modifier.weight(1f))
        }
        Hint(config.engine.description)

        SectionTitle("Strength")
        StrengthCard(
            big = config.depth?.toString() ?: "Max",
            unit = if (config.depth == null) "time-based" else "plies deep",
            description = depthDescription(config.depth),
            presets = presets.size,
            index = depthIdx,
            rangeStart = "Depth 1",
            rangeEnd = "Max",
            onIndex = { onChange(config.copy(depth = presets[it])) },
        )
    }
}
