package io.github.usernamealreadytakensht.games.ui.draughts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.usernamealreadytakensht.games.R
import io.github.usernamealreadytakensht.games.game.draughts.Draughts
import io.github.usernamealreadytakensht.games.game.draughts.DraughtsConfig
import io.github.usernamealreadytakensht.games.game.draughts.DraughtsEngineKind
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
 * Draughts uses the same two-step setup as chess (see ChessSetupScreen.kt): colour and clock
 * first, then the engine and its search depth.
 */

// ---------------------------------------------------------------- screen 1: game

/** First setup screen: colour and clock. [config] is owned by the caller. */
@Composable
fun DraughtsGameSetupScreen(
    config: DraughtsConfig,
    onChange: (DraughtsConfig) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    SetupScaffold(title = "New game", step = "1 / 2", onBack = onBack, action = "Next", onAction = onNext) {
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
    }

private val DraughtsEngineKind.monogram: String
    get() = when (this) {
        DraughtsEngineKind.SCAN -> "Sc"
        DraughtsEngineKind.MOBYDAM -> "MD"
    }

private val DraughtsEngineKind.hue: Float
    get() = when (this) {
        DraughtsEngineKind.SCAN -> 170f
        DraughtsEngineKind.MOBYDAM -> 220f
    }

/** Neither engine has a logo, so the badges carry glyphs drawn for this app. */
private val DraughtsEngineKind.iconRes: Int
    get() = when (this) {
        DraughtsEngineKind.SCAN -> R.drawable.ic_engine_scan
        DraughtsEngineKind.MOBYDAM -> R.drawable.ic_engine_mobydam
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
        summary = "${config.playerSide?.let { if (it == Draughts.Color.WHITE) "White" else "Black" } ?: "Random colour"} · " +
            "${config.timeControl.label} · vs ${config.opponentLabel}",
    ) {
        SectionTitle("Engine")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(IntrinsicSize.Max)) {
            DraughtsEngineKind.entries.forEach { e ->
                OpponentCard(
                    label = e.label,
                    tagline = e.tagline,
                    selected = config.engine == e,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = { onChange(config.copy(engine = e)) },
                ) { EngineBadge(logo = null, monogram = e.monogram, hue = e.hue, icon = e.iconRes) }
            }
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
        Hint("Neither engine has a rating limiter: depth is the only handicap.")
    }
}
