package io.github.usernamealreadytakensht.games.ui.fox

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.usernamealreadytakensht.games.R
import io.github.usernamealreadytakensht.games.game.Takebacks
import io.github.usernamealreadytakensht.games.game.fox.Fox
import io.github.usernamealreadytakensht.games.game.fox.FoxConfig
import io.github.usernamealreadytakensht.games.game.fox.FoxEngineKind
import io.github.usernamealreadytakensht.games.game.fox.FoxVariant
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
 * The fox games use the same two-step setup as the other games: variant, side, clock and
 * takebacks first, then the opponent and its search depth.
 */

private val FoxVariant.icon: Int
    get() = if (this == FoxVariant.HOUNDS) R.drawable.ic_fox_board else R.drawable.ic_geese_board

private val FoxVariant.hunterIcon: Int
    get() = if (this == FoxVariant.HOUNDS) R.drawable.fox_hound else R.drawable.fox_goose

private val FoxVariant.blurb: String
    get() = when (this) {
        FoxVariant.HOUNDS -> "8x8 board, 1 fox vs 4 hounds. Hounds only move forward; the fox wins by slipping past them, the hounds by trapping it. No captures."
        FoxVariant.GEESE -> "Cross board, 1 fox vs 13 geese. The fox jumps geese to capture them, chaining jumps; the geese win by cornering it, the fox once fewer than 6 geese remain."
    }

// ---------------------------------------------------------------- screen 1: game

/** First setup screen: variant, side, clock and takebacks. [config] is owned by the caller. */
@Composable
fun FoxGameSetupScreen(
    config: FoxConfig,
    onChange: (FoxConfig) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    SetupScaffold(title = "New game", step = "1 / 2", onBack = onBack, action = "Next", onAction = onNext) {
        SectionTitle("Game")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(IntrinsicSize.Max)) {
            FoxVariant.entries.forEach { v ->
                SelectableCard(config.variant == v, Modifier.weight(1f).fillMaxHeight(), { onChange(config.copy(variant = v)) }) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp, horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Image(painterResource(v.icon), contentDescription = null, modifier = Modifier.size(48.dp))
                        Text(v.label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                    }
                }
            }
        }
        Hint(config.variant.blurb)

        SectionTitle("Your side")
        SideCards(
            first = "Fox" to listOf(R.drawable.fox_fox),
            second = config.variant.hunterName.replaceFirstChar { it.uppercase() } to listOf(config.variant.hunterIcon),
            selected = config.playerSide?.let { it == Fox.Side.FOX },
            onSelect = { onChange(config.copy(playerSide = it?.let { f -> if (f) Fox.Side.FOX else Fox.Side.HUNTERS })) },
        )
        Hint("The fox moves first.")

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
    depth <= 2 -> "Careless"
    depth <= 4 -> "Casual player"
    depth <= 8 -> "Sees the traps coming"
    else -> "Close to perfect play"
}

/** Second setup screen: opponent and search depth. */
@Composable
fun FoxOpponentSetupScreen(
    config: FoxConfig,
    onChange: (FoxConfig) -> Unit,
    onBack: () -> Unit,
    onPlay: () -> Unit,
) {
    val presets = FoxEngineKind.DEPTH_PRESETS
    val depthIdx = presets.indexOf(config.depth).coerceAtLeast(0)

    SetupScaffold(
        title = "Opponent",
        step = "2 / 2",
        onBack = onBack,
        action = "Play",
        onAction = onPlay,
        summary = "${config.playerSide?.let { if (it == Fox.Side.FOX) "Fox" else config.variant.hunterName.replaceFirstChar { c -> c.uppercase() } } ?: "Random side"} · " +
            "${config.timeControl.label} · vs ${config.opponentLabel}",
    ) {
        SectionTitle("Engine")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(IntrinsicSize.Max)) {
            FoxEngineKind.entries.forEach { e ->
                OpponentCard(
                    label = e.label,
                    tagline = "Built into the app.",
                    selected = config.engine == e,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = { onChange(config.copy(engine = e)) },
                ) { EngineBadge(logo = null, monogram = "Re", hue = 20f, icon = R.drawable.ic_engine_reynard) }
            }
            if (FoxEngineKind.entries.size == 1) Spacer(Modifier.weight(1f))
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
        if (config.variant == FoxVariant.HOUNDS) {
            Hint("Fox and Hounds is solved: the hounds win with perfect play, so playing the fox at high depths is a real challenge.")
        }
    }
}
