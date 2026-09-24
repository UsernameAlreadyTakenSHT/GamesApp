package io.github.usernamealreadytakensht.games.ui.draughts

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
import io.github.usernamealreadytakensht.games.game.draughts.Draughts
import io.github.usernamealreadytakensht.games.game.draughts.DraughtsConfig
import io.github.usernamealreadytakensht.games.game.draughts.DraughtsEngineKind
import io.github.usernamealreadytakensht.games.game.draughts.DraughtsVariant
import io.github.usernamealreadytakensht.games.ui.ClockSection
import io.github.usernamealreadytakensht.games.ui.ColorCards
import io.github.usernamealreadytakensht.games.ui.EngineBadge
import io.github.usernamealreadytakensht.games.ui.Hint
import io.github.usernamealreadytakensht.games.ui.OpponentCard
import io.github.usernamealreadytakensht.games.ui.SectionTitle
import io.github.usernamealreadytakensht.games.ui.SelectableCard
import io.github.usernamealreadytakensht.games.ui.Segmented
import io.github.usernamealreadytakensht.games.ui.SetupScaffold
import io.github.usernamealreadytakensht.games.ui.StrengthCard

/*
 * Draughts uses the same two-step setup as chess (see ChessSetupScreen.kt): colour and clock
 * first, then the engine and its search depth.
 */

// ---------------------------------------------------------------- screen 1: game

/** First setup screen: rules, colour and clock. [config] is owned by the caller. */
@Composable
fun DraughtsGameSetupScreen(
    config: DraughtsConfig,
    onChange: (DraughtsConfig) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    SetupScaffold(title = "New game", step = "1 / 2", onBack = onBack, action = "Next", onAction = onNext) {
        SectionTitle("Rules")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(IntrinsicSize.Max)) {
            DraughtsVariant.entries.forEach { v ->
                SelectableCard(config.variant == v, Modifier.weight(1f).fillMaxHeight(), { onChange(config.withVariant(v)) }) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(v.label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
                        Text(
                            v.tagline,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
        Hint(config.variant.description)

        SectionTitle("Your color")
        ColorCards(
            white = R.drawable.stone_l1,
            black = R.drawable.stone_d1,
            selected = config.playerSide?.let { it == Draughts.Color.WHITE },
            onSelect = { onChange(config.copy(playerSide = it?.let { w -> if (w) Draughts.Color.WHITE else Draughts.Color.BLACK })) },
        )

        ClockSection(config.timeControl) { onChange(config.copy(timeControl = it)) }
    }
}

// ---------------------------------------------------------------- screen 2: opponent

private val DraughtsEngineKind.tagline: String
    get() = when (this) {
        DraughtsEngineKind.SCAN -> "Computer-olympiad champion."
        DraughtsEngineKind.MOBYDAM -> "Strong, different style."
        DraughtsEngineKind.MARCHER -> "NNUE checkers engine."
    }

private val DraughtsEngineKind.monogram: String
    get() = when (this) {
        DraughtsEngineKind.SCAN -> "Sc"
        DraughtsEngineKind.MOBYDAM -> "MD"
        DraughtsEngineKind.MARCHER -> "Ma"
    }

private val DraughtsEngineKind.hue: Float
    get() = when (this) {
        DraughtsEngineKind.SCAN -> 170f
        DraughtsEngineKind.MOBYDAM -> 220f
        DraughtsEngineKind.MARCHER -> 280f
    }

/** None of the engines has a logo, so the badges carry glyphs drawn for this app. */
private val DraughtsEngineKind.iconRes: Int
    get() = when (this) {
        DraughtsEngineKind.SCAN -> R.drawable.ic_engine_scan
        DraughtsEngineKind.MOBYDAM -> R.drawable.ic_engine_mobydam
        DraughtsEngineKind.MARCHER -> R.drawable.ic_engine_marcher
    }

private fun depthDescription(depth: Int?): String = when {
    depth == null -> "As strong as the thinking time allows"
    depth <= 3 -> "Blunders material"
    depth <= 5 -> "Casual player"
    depth <= 8 -> "Decent club game"
    depth <= 12 -> "Strong club player"
    else -> "Very strong"
}

/** Second setup screen: engine and search depth. */
@Composable
fun DraughtsOpponentSetupScreen(
    config: DraughtsConfig,
    onChange: (DraughtsConfig) -> Unit,
    onBack: () -> Unit,
    onPlay: () -> Unit,
) {
    val presets = DraughtsEngineKind.DEPTH_PRESETS
    val depthIdx = presets.indexOf(config.depth).coerceAtLeast(0)

    SetupScaffold(
        title = "Opponent",
        step = "2 / 2",
        onBack = onBack,
        action = "Play",
        onAction = onPlay,
        summary = "${config.variant.label} · " +
            "${config.playerSide?.let { if (it == Draughts.Color.WHITE) "White" else "Black" } ?: "Random colour"} · " +
            "${config.timeControl.label} · vs ${config.opponentLabel}",
    ) {
        SectionTitle("Engine")
        val engines = DraughtsEngineKind.of(config.variant)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(IntrinsicSize.Max)) {
            engines.forEach { e ->
                OpponentCard(
                    label = e.label,
                    tagline = e.tagline,
                    selected = config.engine == e,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = { onChange(config.copy(engine = e)) },
                ) { EngineBadge(logo = null, monogram = e.monogram, hue = e.hue, icon = e.iconRes) }
            }
            if (engines.size == 1) Spacer(Modifier.weight(1f))
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
        Hint("No rating limiter: depth is the handicap.")
    }
}
