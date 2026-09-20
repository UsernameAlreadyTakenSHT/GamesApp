package io.github.usernamealreadytakensht.games.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import kotlin.OptIn
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.usernamealreadytakensht.games.game.EngineFamily
import io.github.usernamealreadytakensht.games.game.EngineKind
import io.github.usernamealreadytakensht.games.game.StrengthKind
import io.github.usernamealreadytakensht.games.game.GameConfig
import io.github.usernamealreadytakensht.games.game.TimeControl
import com.github.bhlangonijr.chesslib.Side
import kotlin.math.roundToInt

private enum class ClockKind(val label: String) {
    NONE("None"), SUDDEN_DEATH("Sudden death"), FISCHER("Fischer"), PER_MOVE("Per move"),
}

/** Values offered by the time-control sliders. */
private val MINUTE_PRESETS = listOf(1, 2, 3, 5, 10, 15, 20, 30, 45, 60, 90)
private val INCREMENT_PRESETS = listOf(0, 1, 2, 3, 5, 10, 15, 20, 30)
private val PER_MOVE_PRESETS = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120)

/** Chess game setup screen. */
@Composable
fun ChessSetupScreen(initial: GameConfig, onBack: () -> Unit, onPlay: (GameConfig) -> Unit) {
    var engine by rememberSaveable { mutableStateOf(initial.engine) }
    // -1 stands for "Max", since rememberSaveable cannot hold a nullable Int.
    var strengthRaw by rememberSaveable { mutableIntStateOf(initial.engine.snap(initial.strength) ?: -1) }
    var sideChoice by rememberSaveable { mutableIntStateOf(sideToChoice(initial.playerSide)) }
    var clockKind by rememberSaveable {
        mutableStateOf(
            when (initial.timeControl) {
                TimeControl.None -> ClockKind.NONE
                is TimeControl.SuddenDeath -> ClockKind.SUDDEN_DEATH
                is TimeControl.Fischer -> ClockKind.FISCHER
                is TimeControl.PerMove -> ClockKind.PER_MOVE
            },
        )
    }
    val tc = initial.timeControl
    var minutesIdx by rememberSaveable {
        mutableIntStateOf(presetIndex(MINUTE_PRESETS, (tc.startingMs ?: 600_000L) / 60_000L, 10))
    }
    var incrementIdx by rememberSaveable {
        val inc = (tc as? TimeControl.Fischer)?.incrementMs ?: 5000L
        mutableIntStateOf(presetIndex(INCREMENT_PRESETS, inc / 1000L, 5))
    }
    var perMoveIdx by rememberSaveable {
        val per = (tc as? TimeControl.PerMove)?.perMoveMs ?: 30_000L
        mutableIntStateOf(presetIndex(PER_MOVE_PRESETS, per / 1000L, 30))
    }

    val strength: Int? = strengthRaw.takeIf { it >= 0 }
    val presets = engine.strengthPresets
    val strengthIdx = presets.indexOf(strength).coerceAtLeast(0)
    val config = remember(engine, strength, sideChoice, clockKind, minutesIdx, incrementIdx, perMoveIdx) {
        GameConfig(
            engine = engine,
            strength = strength,
            playerSide = choiceToSide(sideChoice),
            timeControl = when (clockKind) {
                ClockKind.NONE -> TimeControl.None
                ClockKind.SUDDEN_DEATH -> TimeControl.SuddenDeath(MINUTE_PRESETS[minutesIdx] * 60_000L)
                ClockKind.FISCHER -> TimeControl.Fischer(
                    MINUTE_PRESETS[minutesIdx] * 60_000L,
                    INCREMENT_PRESETS[incrementIdx] * 1000L,
                )
                ClockKind.PER_MOVE -> TimeControl.PerMove(PER_MOVE_PRESETS[perMoveIdx] * 1000L)
            },
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            Button(
                onClick = { onPlay(config) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) { Text("Play") }
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
                Text("Chess — new game", style = MaterialTheme.typography.headlineSmall)
            }

            SectionTitle("Opponent")
            SubLabel("Engine")
            ChipFlow(
                items = EngineFamily.entries,
                selected = engine.family,
                label = { it.label },
                onSelect = { fam ->
                    if (fam != engine.family) {
                        val next = EngineKind.of(fam).first()
                        strengthRaw = next.carryOver(strength, engine) ?: -1
                        engine = next
                    }
                },
            )
            // Options of the selected engine, visually nested under it.
            NestedPanel {
                SubLabel(if (engine.family == EngineFamily.LC0) "Network" else "Version")
                ChipFlow(
                    items = EngineKind.of(engine.family),
                    selected = engine,
                    label = { it.label },
                    onSelect = {
                        strengthRaw = it.carryOver(strength, engine) ?: -1
                        engine = it
                    },
                )
                Text(
                    engine.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                when (engine.strength) {
                    StrengthKind.ELO -> {
                        SubLabel(if (strength == null) "Strength · Max (unlimited)" else "Strength · Elo $strength")
                        StrengthSlider(presets, strengthIdx) { strengthRaw = it ?: -1 }
                        RangeLabels("${engine.eloMin}", "Max")
                    }
                    StrengthKind.NODES -> {
                        SubLabel(
                            if (strength == null) "Search · Max (time-based)"
                            else "Search · $strength node${if (strength > 1) "s" else ""} per move",
                        )
                        StrengthSlider(presets, strengthIdx) { strengthRaw = it ?: -1 }
                        RangeLabels("1 node", "Max")
                    }
                    StrengthKind.HUMAN_ELO -> {
                        SubLabel("Human level · Elo ${strength ?: engine.defaultStrength}")
                        StrengthSlider(presets, strengthIdx) { strengthRaw = it ?: -1 }
                        RangeLabels("${engine.eloMin}", "${engine.eloMax}")
                    }
                    StrengthKind.DEPTH -> {
                        SubLabel(if (strength == null) "Search · Max (time-based)" else "Search · depth $strength")
                        StrengthSlider(presets, strengthIdx) { strengthRaw = it ?: -1 }
                        RangeLabels("Depth 1", "Max")
                    }
                }
            }

            HorizontalDivider()
            SectionTitle("Your color")
            ChipRow(
                items = listOf(0, 1, 2),
                selected = sideChoice,
                label = { listOf("White", "Black", "Random")[it] },
                onSelect = { sideChoice = it },
            )

            HorizontalDivider()
            SectionTitle("Time control")
            ChipRow(
                items = ClockKind.entries,
                selected = clockKind,
                label = { it.label },
                onSelect = { clockKind = it },
            )
            when (clockKind) {
                ClockKind.NONE -> Text(
                    "No time limit.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ClockKind.SUDDEN_DEATH -> PresetSlider(
                    title = "Time per player: ${MINUTE_PRESETS[minutesIdx]} min",
                    presets = MINUTE_PRESETS, index = minutesIdx, unit = "min",
                    onIndex = { minutesIdx = it },
                )
                ClockKind.FISCHER -> {
                    PresetSlider(
                        title = "Initial time: ${MINUTE_PRESETS[minutesIdx]} min",
                        presets = MINUTE_PRESETS, index = minutesIdx, unit = "min",
                        onIndex = { minutesIdx = it },
                    )
                    PresetSlider(
                        title = "Increment per move: ${INCREMENT_PRESETS[incrementIdx]} s",
                        presets = INCREMENT_PRESETS, index = incrementIdx, unit = "s",
                        onIndex = { incrementIdx = it },
                    )
                }
                ClockKind.PER_MOVE -> PresetSlider(
                    title = "Time per move: ${PER_MOVE_PRESETS[perMoveIdx]} s",
                    presets = PER_MOVE_PRESETS, index = perMoveIdx, unit = "s",
                    onIndex = { perMoveIdx = it },
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun <T> ChipRow(items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            FilterChip(
                selected = item == selected,
                onClick = { onSelect(item) },
                label = { Text(label(item)) },
            )
        }
    }
}

/** Notched slider over a list of preset values. */
@Composable
private fun PresetSlider(
    title: String,
    presets: List<Int>,
    index: Int,
    unit: String,
    onIndex: (Int) -> Unit,
) {
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

/** Like [ChipRow] but wraps onto several lines. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipFlow(items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            FilterChip(
                selected = item == selected,
                onClick = { onSelect(item) },
                label = { Text(label(item)) },
            )
        }
    }
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

/** Small caption above a row of options. */
@Composable
private fun SubLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
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
