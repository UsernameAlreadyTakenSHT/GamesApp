package io.github.usernamealreadytakensht.games.ui.draughts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.usernamealreadytakensht.games.game.TimeControl
import io.github.usernamealreadytakensht.games.game.draughts.Draughts
import io.github.usernamealreadytakensht.games.game.draughts.DraughtsConfig
import io.github.usernamealreadytakensht.games.game.draughts.DraughtsEngineKind
import kotlin.math.roundToInt

private val MINUTE_PRESETS = listOf(1, 2, 3, 5, 10, 15, 20, 30, 45, 60, 90)
private val INCREMENT_PRESETS = listOf(0, 1, 2, 3, 5, 10, 15, 20, 30)
private val PER_MOVE_PRESETS = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120)

private enum class ClockKind(val label: String) { NONE("None"), SUDDEN_DEATH("Sudden death"), FISCHER("Fischer"), PER_MOVE("Per move") }

private val TimeControl.clockKind: ClockKind
    get() = when (this) {
        TimeControl.None -> ClockKind.NONE
        is TimeControl.SuddenDeath -> ClockKind.SUDDEN_DEATH
        is TimeControl.Fischer -> ClockKind.FISCHER
        is TimeControl.PerMove -> ClockKind.PER_MOVE
    }

/** Draughts game setup: colour, clock, engine and depth. [config] is owned by the caller. */
@Composable
fun DraughtsSetupScreen(
    config: DraughtsConfig,
    onChange: (DraughtsConfig) -> Unit,
    onBack: () -> Unit,
    onPlay: () -> Unit,
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

    val presets = DraughtsEngineKind.DEPTH_PRESETS
    val depthIdx = presets.indexOf(config.depth).coerceAtLeast(0)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    "${config.playerSide?.let { if (it == Draughts.Color.WHITE) "White" else "Black" } ?: "Random colour"} · " +
                        "${tc.label} · vs ${config.opponentLabel}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Button(onClick = onPlay, modifier = Modifier.fillMaxWidth()) { Text("Play") }
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
                Text("Draughts — new game", style = MaterialTheme.typography.headlineSmall)
            }

            Text("Your colour", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Draughts.Color.WHITE to "White", Draughts.Color.BLACK to "Black", null to "Random").forEach { (side, label) ->
                    FilterChip(selected = config.playerSide == side, onClick = { onChange(config.copy(playerSide = side)) }, label = { Text(label) })
                }
            }

            HorizontalDivider()
            Text("Time control", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ClockKind.entries.forEach { k ->
                    FilterChip(selected = tc.clockKind == k, onClick = { onChange(config.copy(timeControl = clock(k))) }, label = { Text(k.label) })
                }
            }
            when (tc.clockKind) {
                ClockKind.NONE -> Text("No time limit.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ClockKind.SUDDEN_DEATH -> PresetSlider("Time per player: ${MINUTE_PRESETS[minutesIdx]} min", MINUTE_PRESETS, minutesIdx, "min") {
                    onChange(config.copy(timeControl = clock(ClockKind.SUDDEN_DEATH, mIdx = it)))
                }
                ClockKind.FISCHER -> {
                    PresetSlider("Initial time: ${MINUTE_PRESETS[minutesIdx]} min", MINUTE_PRESETS, minutesIdx, "min") {
                        onChange(config.copy(timeControl = clock(ClockKind.FISCHER, mIdx = it)))
                    }
                    PresetSlider("Increment per move: ${INCREMENT_PRESETS[incrementIdx]} s", INCREMENT_PRESETS, incrementIdx, "s") {
                        onChange(config.copy(timeControl = clock(ClockKind.FISCHER, iIdx = it)))
                    }
                }
                ClockKind.PER_MOVE -> PresetSlider("Time per move: ${PER_MOVE_PRESETS[perMoveIdx]} s", PER_MOVE_PRESETS, perMoveIdx, "s") {
                    onChange(config.copy(timeControl = clock(ClockKind.PER_MOVE, pIdx = it)))
                }
            }

            HorizontalDivider()
            Text("Opponent", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DraughtsEngineKind.entries.forEach { e ->
                    FilterChip(selected = config.engine == e, onClick = { onChange(config.copy(engine = e)) }, label = { Text(e.label) })
                }
            }
            Text(config.engine.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (config.depth == null) "Search · Max (time-based)" else "Search · depth ${config.depth}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Slider(
                value = depthIdx.toFloat(),
                onValueChange = { onChange(config.copy(depth = presets[it.roundToInt().coerceIn(presets.indices)])) },
                valueRange = 0f..(presets.size - 1).toFloat(),
                steps = presets.size - 2,
            )
            RangeLabels("Depth 1", "Max")
            Text(
                "Depth 1–3 blunders material, 6 plays a decent club game, 12+ is very strong. Both engines have no rating limiter.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

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
