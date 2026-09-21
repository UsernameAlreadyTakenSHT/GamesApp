package io.github.usernamealreadytakensht.games.ui.tafl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.usernamealreadytakensht.games.R
import io.github.usernamealreadytakensht.games.game.Takebacks
import io.github.usernamealreadytakensht.games.game.tafl.Tafl
import io.github.usernamealreadytakensht.games.game.tafl.TaflConfig
import io.github.usernamealreadytakensht.games.game.tafl.TaflEngineKind
import io.github.usernamealreadytakensht.games.ui.ClockSection
import io.github.usernamealreadytakensht.games.ui.EngineBadge
import io.github.usernamealreadytakensht.games.ui.Hint
import io.github.usernamealreadytakensht.games.ui.OpponentCard
import io.github.usernamealreadytakensht.games.ui.SectionTitle
import io.github.usernamealreadytakensht.games.ui.Segmented
import io.github.usernamealreadytakensht.games.ui.SetupScaffold
import io.github.usernamealreadytakensht.games.ui.SideCards
import io.github.usernamealreadytakensht.games.ui.StrengthCard

/*
 * Hnefatafl uses the same two-step setup as the other games: side, clock and takebacks,
 * then the opponent and its search depth.
 */

// ---------------------------------------------------------------- screen 1: game

/** First setup screen: side, clock and takebacks. [config] is owned by the caller. */
@Composable
fun TaflGameSetupScreen(
    config: TaflConfig,
    onChange: (TaflConfig) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    SetupScaffold(title = "New game", step = "1 / 2", onBack = onBack, action = "Next", onAction = onNext) {
        SectionTitle("Your side")
        SideCards(
            first = "Defenders" to listOf(R.drawable.tafl_king, R.drawable.tafl_defender),
            second = "Attackers" to listOf(R.drawable.tafl_attacker),
            selected = config.playerSide?.let { it == Tafl.Side.DEFENDERS },
            onSelect = { onChange(config.copy(playerSide = it?.let { d -> if (d) Tafl.Side.DEFENDERS else Tafl.Side.ATTACKERS })) },
        )
        Hint("Attackers move first. Defenders win by walking the king to a corner; attackers by surrounding him on four sides.")

        ClockSection(config.timeControl) { onChange(config.copy(timeControl = it)) }

        SectionTitle("Takebacks")
        Segmented(
            items = Takebacks.entries,
            selected = config.takebacks,
            label = { it.label },
            onSelect = { onChange(config.copy(takebacks = it)) },
        )
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
        summary = "${config.playerSide?.let { if (it == Tafl.Side.DEFENDERS) "Defenders" else "Attackers" } ?: "Random side"} · " +
            "${config.timeControl.label} · vs ${config.opponentLabel}",
    ) {
        SectionTitle("Engine")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(IntrinsicSize.Max)) {
            TaflEngineKind.entries.forEach { e ->
                OpponentCard(
                    label = e.label,
                    tagline = "Copenhagen rules and AI.",
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
