package io.github.usernamealreadytakensht.games.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.bhlangonijr.chesslib.Side
import io.github.usernamealreadytakensht.games.game.EngineFamily
import io.github.usernamealreadytakensht.games.game.EngineKind
import io.github.usernamealreadytakensht.games.game.GameConfig
import io.github.usernamealreadytakensht.games.game.StrengthKind
import io.github.usernamealreadytakensht.games.game.Takebacks
import io.github.usernamealreadytakensht.games.game.ThinkingTime
import io.github.usernamealreadytakensht.games.game.TimeControl
import kotlin.math.roundToInt

private enum class ClockKind(val label: String) {
    NONE("None"), SUDDEN_DEATH("Sudden death"), FISCHER("Fischer"), PER_MOVE("Per move"),
}

/** Values offered by the time-control sliders. */
private val MINUTE_PRESETS = listOf(1, 2, 3, 5, 10, 15, 20, 30, 45, 60, 90)
private val INCREMENT_PRESETS = listOf(0, 1, 2, 3, 5, 10, 15, 20, 30)
private val PER_MOVE_PRESETS = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120)

private val TimeControl.clockKind: ClockKind
    get() = when (this) {
        TimeControl.None -> ClockKind.NONE
        is TimeControl.SuddenDeath -> ClockKind.SUDDEN_DEATH
        is TimeControl.Fischer -> ClockKind.FISCHER
        is TimeControl.PerMove -> ClockKind.PER_MOVE
    }

// ---------------------------------------------------------------- screen 1: game

/** First setup screen: colour, clock and game options. [config] is owned by the caller. */
@Composable
fun GameSetupScreen(
    config: GameConfig,
    onChange: (GameConfig) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    val tc = config.timeControl
    val minutes = ((tc as? TimeControl.SuddenDeath)?.initialMs ?: (tc as? TimeControl.Fischer)?.initialMs ?: 600_000L) / 60_000L
    val increment = ((tc as? TimeControl.Fischer)?.incrementMs ?: 5000L) / 1000L
    val perMove = ((tc as? TimeControl.PerMove)?.perMoveMs ?: 30_000L) / 1000L
    val minutesIdx = presetIndex(MINUTE_PRESETS, minutes, 10)
    val incrementIdx = presetIndex(INCREMENT_PRESETS, increment, 5)
    val perMoveIdx = presetIndex(PER_MOVE_PRESETS, perMove, 30)

    fun clock(kind: ClockKind, mIdx: Int = minutesIdx, iIdx: Int = incrementIdx, pIdx: Int = perMoveIdx): TimeControl =
        when (kind) {
            ClockKind.NONE -> TimeControl.None
            ClockKind.SUDDEN_DEATH -> TimeControl.SuddenDeath(MINUTE_PRESETS[mIdx] * 60_000L)
            ClockKind.FISCHER -> TimeControl.Fischer(MINUTE_PRESETS[mIdx] * 60_000L, INCREMENT_PRESETS[iIdx] * 1000L)
            ClockKind.PER_MOVE -> TimeControl.PerMove(PER_MOVE_PRESETS[pIdx] * 1000L)
        }

    SetupScaffold(title = "New game · 1/2", onBack = onBack, action = "Next", onAction = onNext) {
        SectionTitle("Your color")
        ChipRow(
            items = listOf(0, 1, 2),
            selected = sideToChoice(config.playerSide),
            label = { listOf("White", "Black", "Random")[it] },
            onSelect = { onChange(config.copy(playerSide = choiceToSide(it))) },
        )

        HorizontalDivider()
        SectionTitle("Time control")
        ChipRow(
            items = ClockKind.entries,
            selected = tc.clockKind,
            label = { it.label },
            onSelect = { onChange(config.copy(timeControl = clock(it))) },
        )
        when (tc.clockKind) {
            ClockKind.NONE -> Text(
                "No time limit.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ClockKind.SUDDEN_DEATH -> PresetSlider(
                title = "Time per player: ${MINUTE_PRESETS[minutesIdx]} min",
                presets = MINUTE_PRESETS, index = minutesIdx, unit = "min",
                onIndex = { onChange(config.copy(timeControl = clock(ClockKind.SUDDEN_DEATH, mIdx = it))) },
            )
            ClockKind.FISCHER -> {
                PresetSlider(
                    title = "Initial time: ${MINUTE_PRESETS[minutesIdx]} min",
                    presets = MINUTE_PRESETS, index = minutesIdx, unit = "min",
                    onIndex = { onChange(config.copy(timeControl = clock(ClockKind.FISCHER, mIdx = it))) },
                )
                PresetSlider(
                    title = "Increment per move: ${INCREMENT_PRESETS[incrementIdx]} s",
                    presets = INCREMENT_PRESETS, index = incrementIdx, unit = "s",
                    onIndex = { onChange(config.copy(timeControl = clock(ClockKind.FISCHER, iIdx = it))) },
                )
            }
            ClockKind.PER_MOVE -> PresetSlider(
                title = "Time per move: ${PER_MOVE_PRESETS[perMoveIdx]} s",
                presets = PER_MOVE_PRESETS, index = perMoveIdx, unit = "s",
                onIndex = { onChange(config.copy(timeControl = clock(ClockKind.PER_MOVE, pIdx = it))) },
            )
        }

        HorizontalDivider()
        SectionTitle("Options")
        SubLabel("Takebacks")
        ChipRow(
            items = Takebacks.entries,
            selected = config.takebacks,
            label = { it.label },
            onSelect = { onChange(config.copy(takebacks = it)) },
        )
        SwitchRow("Show legal moves", "Dots on the squares a selected piece can go to", config.showLegalMoves) {
            onChange(config.copy(showLegalMoves = it))
        }
        SwitchRow("Confirm moves", "Tap the destination twice (or Confirm) to play", config.confirmMoves) {
            onChange(config.copy(confirmMoves = it))
        }
        SwitchRow("Auto-queen", "Promote to a queen without asking", config.autoQueen) {
            onChange(config.copy(autoQueen = it))
        }
        SubLabel("Engine thinking time")
        ChipRow(
            items = ThinkingTime.entries,
            selected = config.thinking,
            label = { it.label },
            onSelect = { onChange(config.copy(thinking = it)) },
        )
        Text(
            "Scales how long the engine thinks per move (${config.thinking.factor}x). Useful for the slower Leela networks.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------------------------------------------------------- screen 2: opponent

/** Second setup screen: engine, version/network and strength. */
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
        title = "New game · 2/2",
        onBack = onBack,
        action = "Play",
        onAction = onPlay,
        summary = "${config.playerSide?.let { if (it == Side.WHITE) "White" else "Black" } ?: "Random colour"} · " +
            "${config.timeControl.label} · vs ${config.opponentLabel}",
    ) {
        SectionTitle("Opponent")
        SubLabel("Engine")
        ChipFlow(
            items = EngineFamily.entries,
            selected = engine.family,
            label = { it.label },
            onSelect = { fam -> if (fam != engine.family) pick(EngineKind.of(fam).first()) },
        )
        // Options of the selected engine, visually nested under it.
        NestedPanel {
            SubLabel(if (engine.family == EngineFamily.LC0) "Network" else "Version")
            ChipFlow(
                items = EngineKind.of(engine.family),
                selected = engine,
                label = { it.label },
                onSelect = { pick(it) },
            )
            Text(
                engine.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            val setStrength = { v: Int? -> onChange(config.copy(strength = v)) }
            when (engine.strength) {
                StrengthKind.ELO -> {
                    SubLabel(if (strength == null) "Strength · Max (unlimited)" else "Strength · Elo $strength")
                    StrengthSlider(presets, strengthIdx, setStrength)
                    RangeLabels("${engine.eloMin}", "Max")
                }
                StrengthKind.NODES -> {
                    SubLabel(
                        if (strength == null) "Search · Max (time-based)"
                        else "Search · $strength node${if (strength > 1) "s" else ""} per move",
                    )
                    StrengthSlider(presets, strengthIdx, setStrength)
                    RangeLabels("1 node", "Max")
                }
                StrengthKind.HUMAN_ELO -> {
                    SubLabel("Human level · Elo ${strength ?: engine.defaultStrength}")
                    StrengthSlider(presets, strengthIdx, setStrength)
                    RangeLabels("${engine.eloMin}", "${engine.eloMax}")
                }
                StrengthKind.DEPTH -> {
                    SubLabel(if (strength == null) "Search · Max (time-based)" else "Search · depth $strength")
                    StrengthSlider(presets, strengthIdx, setStrength)
                    RangeLabels("Depth 1", "Max")
                }
            }
        }
    }
}

// ---------------------------------------------------------------- shared pieces

/** Common frame of the two setup screens: back arrow, title, scrolling body, bottom action. */
@Composable
private fun SetupScaffold(
    title: String,
    onBack: () -> Unit,
    action: String,
    onAction: () -> Unit,
    summary: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                if (summary != null) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) { Text(action) }
            }
        },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) }
                Text(title, style = MaterialTheme.typography.headlineSmall)
            }
            content()
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

/** Small caption above a row of options. */
@Composable
private fun SubLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun <T> ChipRow(items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            FilterChip(selected = item == selected, onClick = { onSelect(item) }, label = { Text(label(item)) })
        }
    }
}

/** Like [ChipRow] but wraps onto several lines. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipFlow(items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            FilterChip(selected = item == selected, onClick = { onSelect(item) }, label = { Text(label(item)) })
        }
    }
}

/** Indented, tinted box that shows its content depends on the option above it. */
@Composable
private fun NestedPanel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** Notched slider over strength presets; the callback receives the chosen preset (null = max). */
@Composable
private fun StrengthSlider(presets: List<Int?>, index: Int, onPick: (Int?) -> Unit) {
    Slider(
        value = index.toFloat(),
        onValueChange = { onPick(presets[it.roundToInt().coerceIn(presets.indices)]) },
        valueRange = 0f..(presets.size - 1).toFloat(),
        steps = presets.size - 2,
    )
}

/** Notched slider over a list of preset values. */
@Composable
private fun PresetSlider(title: String, presets: List<Int>, index: Int, unit: String, onIndex: (Int) -> Unit) {
    Column {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Slider(
            value = index.toFloat(),
            onValueChange = { onIndex(it.roundToInt().coerceIn(presets.indices)) },
            valueRange = 0f..(presets.size - 1).toFloat(),
            steps = presets.size - 2,
        )
        RangeLabels("${presets.first()} $unit", "${presets.last()} $unit")
    }
}

@Composable
private fun RangeLabels(start: String, end: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(start, style = MaterialTheme.typography.labelSmall)
        Text(end, style = MaterialTheme.typography.labelSmall)
    }
}

private fun presetIndex(presets: List<Int>, value: Long, fallback: Int): Int =
    presets.indexOf(value.toInt()).takeIf { it >= 0 } ?: presets.indexOf(fallback)

private fun sideToChoice(side: Side?) = when (side) {
    Side.WHITE -> 0
    Side.BLACK -> 1
    null -> 2
}

private fun choiceToSide(choice: Int): Side? = when (choice) {
    0 -> Side.WHITE
    1 -> Side.BLACK
    else -> null
}
