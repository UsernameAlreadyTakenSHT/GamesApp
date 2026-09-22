package io.github.usernamealreadytakensht.games.ui.morris

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
import io.github.usernamealreadytakensht.games.game.morris.Morris
import io.github.usernamealreadytakensht.games.game.morris.MorrisConfig
import io.github.usernamealreadytakensht.games.game.morris.MorrisEngineFamily
import io.github.usernamealreadytakensht.games.game.morris.MorrisEngineKind
import io.github.usernamealreadytakensht.games.ui.ChipFlow
import io.github.usernamealreadytakensht.games.ui.ClockSection
import io.github.usernamealreadytakensht.games.ui.ColorCards
import io.github.usernamealreadytakensht.games.ui.EngineBadge
import io.github.usernamealreadytakensht.games.ui.Hint
import io.github.usernamealreadytakensht.games.ui.OpponentCard
import io.github.usernamealreadytakensht.games.ui.SectionTitle
import io.github.usernamealreadytakensht.games.ui.Segmented
import io.github.usernamealreadytakensht.games.ui.SetupScaffold
import io.github.usernamealreadytakensht.games.ui.StrengthCard

/*
 * Nine Men's Morris uses the same two-step setup as chess and draughts: colour, clock and
 * clock first, then the opponent and its search depth.
 */

// ---------------------------------------------------------------- screen 1: game

/** First setup screen: colour and clock. [config] is owned by the caller. */
@Composable
fun MorrisGameSetupScreen(
    config: MorrisConfig,
    onChange: (MorrisConfig) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    SetupScaffold(title = "New game", step = "1 / 2", onBack = onBack, action = "Next", onAction = onNext) {
        SectionTitle("Your color")
        ColorCards(
            white = R.drawable.stone_l1,
            black = R.drawable.stone_d1,
            selected = config.playerSide?.let { it == Morris.Color.WHITE },
            onSelect = { onChange(config.copy(playerSide = it?.let { w -> if (w) Morris.Color.WHITE else Morris.Color.BLACK })) },
        )

        ClockSection(config.timeControl) { onChange(config.copy(timeControl = it)) }

    }
}

// ---------------------------------------------------------------- screen 2: opponent

private fun depthDescription(depth: Int?): String = when {
    depth == null -> "As strong as the thinking time allows"
    depth <= 2 -> "Misses simple mills"
    depth <= 4 -> "Casual player"
    depth <= 6 -> "Solid club opponent"
    depth <= 10 -> "Strong: sees most traps"
    else -> "Very hard to beat"
}

/** Second setup screen: engine family, Sanmill's algorithm, and search depth. */
@Composable
fun MorrisOpponentSetupScreen(
    config: MorrisConfig,
    onChange: (MorrisConfig) -> Unit,
    onBack: () -> Unit,
    onPlay: () -> Unit,
) {
    val presets = MorrisEngineKind.DEPTH_PRESETS
    val depthIdx = presets.indexOf(config.depth).coerceAtLeast(0)
    val engine = config.engine

    SetupScaffold(
        title = "Opponent",
        step = "2 / 2",
        onBack = onBack,
        action = "Play",
        onAction = onPlay,
        summary = "${config.playerSide?.let { if (it == Morris.Color.WHITE) "White" else "Black" } ?: "Random colour"} · " +
            "${config.timeControl.label} · vs ${config.opponentLabel}",
    ) {
        SectionTitle("Engine")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(IntrinsicSize.Max)) {
            MorrisEngineFamily.entries.forEach { fam ->
                OpponentCard(
                    label = fam.label,
                    tagline = fam.tagline,
                    selected = engine.family == fam,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = { if (engine.family != fam) onChange(config.copy(engine = MorrisEngineKind.of(fam).first())) },
                ) { EngineBadge(logo = R.drawable.logo_sanmill, monogram = "Sa", hue = 30f) }
            }
            if (MorrisEngineFamily.entries.size == 1) Spacer(Modifier.weight(1f))
        }
        val variants = MorrisEngineKind.of(engine.family)
        if (variants.size > 1) {
            SectionTitle("Algorithm")
            ChipFlow(items = variants, selected = engine, label = { it.label }, onSelect = { onChange(config.copy(engine = it)) })
        }
        Hint(engine.description)

        SectionTitle("Strength")
        val mcts = engine == MorrisEngineKind.SANMILL_MCTS
        StrengthCard(
            big = config.depth?.toString() ?: "Max",
            unit = if (config.depth == null) "time-based" else if (mcts) "× 2048 simulations" else "plies deep",
            description = depthDescription(config.depth),
            presets = presets.size,
            index = depthIdx,
            rangeStart = if (mcts) "Level 1" else "Depth 1",
            rangeEnd = "Max",
            onIndex = { onChange(config.copy(depth = presets[it])) },
        )
    }
}
