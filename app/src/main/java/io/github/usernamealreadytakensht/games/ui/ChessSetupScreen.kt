package io.github.usernamealreadytakensht.games.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.bhlangonijr.chesslib.Side
import io.github.usernamealreadytakensht.games.R
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

    SetupScaffold(title = "New game", step = "1 / 2", onBack = onBack, action = "Next", onAction = onNext) {
        SectionTitle("Your color")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ColorCard("White", listOf(R.drawable.piece_lk), config.playerSide == Side.WHITE, Modifier.weight(1f)) {
                onChange(config.copy(playerSide = Side.WHITE))
            }
            ColorCard("Black", listOf(R.drawable.piece_dk), config.playerSide == Side.BLACK, Modifier.weight(1f)) {
                onChange(config.copy(playerSide = Side.BLACK))
            }
            ColorCard("Random", listOf(R.drawable.piece_lk, R.drawable.piece_dk), config.playerSide == null, Modifier.weight(1f)) {
                onChange(config.copy(playerSide = null))
            }
        }

        SectionTitle("Time control")
        ChipFlow(
            items = ClockKind.entries,
            selected = tc.clockKind,
            label = { it.label },
            onSelect = { onChange(config.copy(timeControl = clock(it))) },
        )
        when (tc.clockKind) {
            ClockKind.NONE -> Hint("No time limit. Take all the time you need.")
            ClockKind.SUDDEN_DEATH -> ValueSlider(
                title = "Time per player", value = "${MINUTE_PRESETS[minutesIdx]} min",
                presets = MINUTE_PRESETS, index = minutesIdx, unit = "min",
                onIndex = { onChange(config.copy(timeControl = clock(ClockKind.SUDDEN_DEATH, mIdx = it))) },
            )
            ClockKind.FISCHER -> {
                ValueSlider(
                    title = "Initial time", value = "${MINUTE_PRESETS[minutesIdx]} min",
                    presets = MINUTE_PRESETS, index = minutesIdx, unit = "min",
                    onIndex = { onChange(config.copy(timeControl = clock(ClockKind.FISCHER, mIdx = it))) },
                )
                ValueSlider(
                    title = "Increment per move", value = "+${INCREMENT_PRESETS[incrementIdx]} s",
                    presets = INCREMENT_PRESETS, index = incrementIdx, unit = "s",
                    onIndex = { onChange(config.copy(timeControl = clock(ClockKind.FISCHER, iIdx = it))) },
                )
            }
            ClockKind.PER_MOVE -> ValueSlider(
                title = "Time per move", value = "${PER_MOVE_PRESETS[perMoveIdx]} s",
                presets = PER_MOVE_PRESETS, index = perMoveIdx, unit = "s",
                onIndex = { onChange(config.copy(timeControl = clock(ClockKind.PER_MOVE, pIdx = it))) },
            )
        }

        SectionTitle("Options")
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingLabel("Takebacks")
                Segmented(
                    items = Takebacks.entries,
                    selected = config.takebacks,
                    label = { it.label },
                    onSelect = { onChange(config.copy(takebacks = it)) },
                )
                HorizontalDivider()
                SwitchRow("Show legal moves", "Dots on the squares a selected piece can go to", config.showLegalMoves) {
                    onChange(config.copy(showLegalMoves = it))
                }
                SwitchRow("Confirm moves", "Tap the destination twice (or Confirm) to play", config.confirmMoves) {
                    onChange(config.copy(confirmMoves = it))
                }
                SwitchRow("Auto-queen", "Promote to a queen without asking", config.autoQueen) {
                    onChange(config.copy(autoQueen = it))
                }
                HorizontalDivider()
                SettingLabel("Engine thinking time")
                Segmented(
                    items = ThinkingTime.entries,
                    selected = config.thinking,
                    label = { "${it.label} ×${it.factor.let { f -> if (f == f.toInt().toDouble()) f.toInt().toString() else f.toString() }}" },
                    onSelect = { onChange(config.copy(thinking = it)) },
                )
                Hint("Scales how long the engine thinks per move. Useful for the slower Leela networks.")
            }
        }
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
                    EngineCard(fam, selected = fam == engine.family, modifier = Modifier.weight(1f).fillMaxHeight()) {
                        if (fam != engine.family) pick(EngineKind.of(fam).first())
                    }
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
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val (big, unit) = when (engine.strength) {
                    StrengthKind.ELO -> (strength?.toString() ?: "Max") to (if (strength == null) "unlimited" else "Elo")
                    StrengthKind.HUMAN_ELO -> "${strength ?: engine.defaultStrength}" to "Elo, human level"
                    StrengthKind.NODES -> (strength?.toString() ?: "Max") to
                        (if (strength == null) "time-based" else "node${if (strength > 1) "s" else ""} per move")
                    StrengthKind.DEPTH -> (strength?.toString() ?: "Max") to
                        (if (strength == null) "time-based" else "plies deep")
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(big, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    Text(
                        unit,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                Text(engine.levelDescription(strength), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Slider(
                    value = strengthIdx.toFloat(),
                    onValueChange = { onChange(config.copy(strength = presets[it.roundToInt().coerceIn(presets.indices)])) },
                    valueRange = 0f..(presets.size - 1).toFloat(),
                    steps = presets.size - 2,
                )
                val (lo, hi) = when (engine.strength) {
                    StrengthKind.ELO -> "${engine.eloMin}" to "Max"
                    StrengthKind.HUMAN_ELO -> "${engine.eloMin}" to "${engine.eloMax}"
                    StrengthKind.NODES -> "1 node" to "Max"
                    StrengthKind.DEPTH -> "Depth 1" to "Max"
                }
                RangeLabels(lo, hi)
            }
        }
    }
}

// ---------------------------------------------------------------- shared pieces

/** Common frame of the two setup screens: back arrow, title, scrolling body, bottom action. */
@Composable
private fun SetupScaffold(
    title: String,
    step: String,
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
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    )
                }
                Button(onClick = onAction, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(action, style = MaterialTheme.typography.titleMedium)
                }
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
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = onBack) { Text("←", style = MaterialTheme.typography.titleLarge) }
                Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                Text(
                    step,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
            content()
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun SettingLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Selectable card showing one or two king glyphs. */
@Composable
private fun ColorCard(label: String, icons: List<Int>, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    SelectableCard(selected, modifier, onClick) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy((-8).dp)) {
                icons.forEach { Image(painterResource(it), contentDescription = null, modifier = Modifier.size(40.dp)) }
            }
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Engine family card: coloured monogram badge, name and tagline. */
@Composable
private fun EngineCard(family: EngineFamily, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    SelectableCard(selected, modifier, onClick) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(Color.hsl(family.hue, 0.45f, 0.42f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(family.monogram, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(family.label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    family.tagline,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun SelectableCard(selected: Boolean, modifier: Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                             else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) { content() }
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
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun <T> Segmented(items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        items.forEachIndexed { i, item ->
            SegmentedButton(
                selected = item == selected,
                onClick = { onSelect(item) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = items.size),
            ) { Text(label(item), maxLines = 1) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipFlow(items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            FilterChip(selected = item == selected, onClick = { onSelect(item) }, label = { Text(label(item)) })
        }
    }
}

/** Slider over preset values with the current value shown as a pill on the right. */
@Composable
private fun ValueSlider(title: String, value: String, presets: List<Int>, index: Int, unit: String, onIndex: (Int) -> Unit) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                value,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
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
        Text(start, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(end, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun presetIndex(presets: List<Int>, value: Long, fallback: Int): Int =
    presets.indexOf(value.toInt()).takeIf { it >= 0 } ?: presets.indexOf(fallback)
