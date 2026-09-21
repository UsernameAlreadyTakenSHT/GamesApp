package io.github.usernamealreadytakensht.games.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.bhlangonijr.chesslib.Side
import io.github.usernamealreadytakensht.games.R
import io.github.usernamealreadytakensht.games.game.EngineFamily
import io.github.usernamealreadytakensht.games.game.EngineKind
import io.github.usernamealreadytakensht.games.game.GameConfig
import io.github.usernamealreadytakensht.games.game.StrengthKind
import io.github.usernamealreadytakensht.games.game.Takebacks
import io.github.usernamealreadytakensht.games.game.ThinkingTime

// ---------------------------------------------------------------- screen 1: game

/** First setup screen: colour, clock and takebacks. [config] is owned by the caller. */
@Composable
fun GameSetupScreen(
    config: GameConfig,
    onChange: (GameConfig) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    SetupScaffold(title = "New game", step = "1 / 2", onBack = onBack, action = "Next", onAction = onNext) {
        SectionTitle("Your color")
        ColorCards(
            white = R.drawable.piece_lk,
            black = R.drawable.piece_dk,
            selected = config.playerSide?.let { it == Side.WHITE },
            onSelect = { onChange(config.copy(playerSide = it?.let { w -> if (w) Side.WHITE else Side.BLACK })) },
        )

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

/** Official logo of an engine family (see THIRD_PARTY.md), or null for the monogram badge. */
private val EngineFamily.logoRes: Int?
    get() = when (this) {
        EngineFamily.STOCKFISH -> R.drawable.logo_stockfish
        EngineFamily.LC0 -> R.drawable.logo_lc0
        EngineFamily.RECKLESS -> R.drawable.logo_reckless
        EngineFamily.SUNFISH -> R.drawable.logo_sunfish
        EngineFamily.MAIA -> R.drawable.logo_maia
        EngineFamily.RODENT -> R.drawable.logo_rodent
        else -> null
    }

/** Glyph drawn on the coloured disc for engines without a logo (our own artwork). */
private val EngineFamily.iconRes: Int?
    get() = when (this) {
        EngineFamily.BERSERK -> R.drawable.ic_engine_berserk
        EngineFamily.PLENTY -> R.drawable.ic_engine_plenty
        else -> null
    }

/** Second setup screen: engine, version/network, strength and thinking time. */
@Composable
fun OpponentSetupScreen(
    config: GameConfig,
    onChange: (GameConfig) -> Unit,
    onBack: () -> Unit,
    onPlay: () -> Unit,
) {
    val engine = config.engine
    val strength = config.strength
    val presets = engine.strengthPresets
    val strengthIdx = presets.indexOf(strength).coerceAtLeast(0)

    fun pick(next: EngineKind) = onChange(config.copy(engine = next, strength = next.carryOver(strength, engine)))

    SetupScaffold(
        title = "Opponent",
        step = "2 / 2",
        onBack = onBack,
        action = "Play",
        onAction = onPlay,
        summary = "${config.playerSide?.let { if (it == Side.WHITE) "White" else "Black" } ?: "Random colour"} · " +
            "${config.timeControl.label} · vs ${config.opponentLabel}",
    ) {
        SectionTitle("Engine")
        // Two cards per row.
        EngineFamily.entries.chunked(2).forEach { pair ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.height(IntrinsicSize.Max),
            ) {
                pair.forEach { fam ->
                    OpponentCard(
                        label = fam.label,
                        tagline = fam.tagline,
                        selected = fam == engine.family,
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        onClick = { if (fam != engine.family) pick(EngineKind.of(fam).first()) },
                    ) { EngineBadge(fam.logoRes, fam.monogram, fam.hue, fam.iconRes) }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        val variants = EngineKind.of(engine.family)
        if (variants.size > 1) {
            SectionTitle(if (engine.family == EngineFamily.LC0) "Network" else "Version")
            ChipFlow(items = variants, selected = engine, label = { it.label }, onSelect = { pick(it) })
        }
        Hint(engine.description)

        SectionTitle("Strength")
        val (big, unit) = when (engine.strength) {
            StrengthKind.ELO -> (strength?.toString() ?: "Max") to (if (strength == null) "unlimited" else "Elo")
            StrengthKind.HUMAN_ELO -> "${strength ?: engine.defaultStrength}" to "Elo, human level"
            StrengthKind.NODES -> (strength?.toString() ?: "Max") to
                (if (strength == null) "time-based" else "node${if (strength > 1) "s" else ""} per move")
            StrengthKind.DEPTH -> (strength?.toString() ?: "Max") to
                (if (strength == null) "time-based" else "plies deep")
        }
        val (lo, hi) = when (engine.strength) {
            StrengthKind.ELO -> "${engine.eloMin}" to "Max"
            StrengthKind.HUMAN_ELO -> "${engine.eloMin}" to "${engine.eloMax}"
            StrengthKind.NODES -> "1 node" to "Max"
            StrengthKind.DEPTH -> "Depth 1" to "Max"
        }
        StrengthCard(
            big = big,
            unit = unit,
            description = engine.levelDescription(strength),
            presets = presets.size,
            index = strengthIdx,
            rangeStart = lo,
            rangeEnd = hi,
            onIndex = { onChange(config.copy(strength = presets[it])) },
        )

        SectionTitle("Thinking time")
        Segmented(
            items = ThinkingTime.entries,
            selected = config.thinking,
            label = { "${it.label} ×${it.factor.let { f -> if (f == f.toInt().toDouble()) f.toInt().toString() else f.toString() }}" },
            onSelect = { onChange(config.copy(thinking = it)) },
        )
        Hint("Scales how long the engine thinks per move. Useful for the slower Leela networks.")
    }
}
