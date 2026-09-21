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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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
    NONE("None"), SUDDEN_DEATH("Sudden"), FISCHER("Fischer"), PER_MOVE("Per move"),
}

private val TimeControl.clockKind: ClockKind
    get() = when (this) {
        TimeControl.None -> ClockKind.NONE
        is TimeControl.SuddenDeath -> ClockKind.SUDDEN_DEATH
        is TimeControl.Fischer -> ClockKind.FISCHER
        is TimeControl.PerMove -> ClockKind.PER_MOVE
    }

/** Quick picks shown under the clock fields: label to (minutes, increment seconds). */
private val FISCHER_PRESETS = listOf("1+0" to (1 to 0), "3+2" to (3 to 2), "5+0" to (5 to 0), "10+5" to (10 to 5), "15+10" to (15 to 10))

// ---------------------------------------------------------------- screen 1: game

/** First setup screen: colour, clock and takebacks. [config] is owned by the caller. */
@Composable
fun GameSetupScreen(
    config: GameConfig,
    onChange: (GameConfig) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
) {
    val tc = config.timeControl
    // Values remembered across clock kinds so switching does not lose what was typed.
    val minutes = ((tc as? TimeControl.SuddenDeath)?.initialMs ?: (tc as? TimeControl.Fischer)?.initialMs ?: 600_000L) / 60_000L
    val increment = ((tc as? TimeControl.Fischer)?.incrementMs ?: 5000L) / 1000L
    val perMove = ((tc as? TimeControl.PerMove)?.perMoveMs ?: 30_000L) / 1000L

    fun clock(kind: ClockKind, m: Long = minutes, i: Long = increment, p: Long = perMove): TimeControl = when (kind) {
        ClockKind.NONE -> TimeControl.None
        ClockKind.SUDDEN_DEATH -> TimeControl.SuddenDeath(m.coerceIn(1, 999) * 60_000L)
        ClockKind.FISCHER -> TimeControl.Fischer(m.coerceIn(1, 999) * 60_000L, i.coerceIn(0, 999) * 1000L)
        ClockKind.PER_MOVE -> TimeControl.PerMove(p.coerceIn(1, 999) * 1000L)
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
        Segmented(
            items = ClockKind.entries,
            selected = tc.clockKind,
            label = { it.label },
            onSelect = { onChange(config.copy(timeControl = clock(it))) },
        )
        when (tc.clockKind) {
            ClockKind.NONE -> Hint("No time limit.")
            ClockKind.SUDDEN_DEATH -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(minutes, "min", Modifier.weight(1f)) { onChange(config.copy(timeControl = clock(ClockKind.SUDDEN_DEATH, m = it))) }
                Text("per player", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(2f))
            }
            ClockKind.FISCHER -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(minutes, "min", Modifier.weight(1f)) { onChange(config.copy(timeControl = clock(ClockKind.FISCHER, m = it))) }
                    Text("+", style = MaterialTheme.typography.titleLarge)
                    NumberField(increment, "s / move", Modifier.weight(1f)) { onChange(config.copy(timeControl = clock(ClockKind.FISCHER, i = it))) }
                    Spacer(Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FISCHER_PRESETS.forEach { (label, mi) ->
                        val (m, i) = mi
                        FilterChip(
                            selected = minutes == m.toLong() && increment == i.toLong(),
                            onClick = { onChange(config.copy(timeControl = clock(ClockKind.FISCHER, m = m.toLong(), i = i.toLong()))) },
                            label = { Text(label) },
                        )
                    }
                }
            }
            ClockKind.PER_MOVE -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(perMove, "s", Modifier.weight(1f)) { onChange(config.copy(timeControl = clock(ClockKind.PER_MOVE, p = it))) }
                Text("per move, reset each move", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(2f))
            }
        }

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
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Compact numeric field; empty or invalid input leaves the value unchanged. */
@Composable
private fun NumberField(value: Long, suffix: String, modifier: Modifier, onValue: (Long) -> Unit) {
    OutlinedTextField(
        value = value.toString(),
        onValueChange = { text -> text.filter { it.isDigit() }.take(3).toLongOrNull()?.let(onValue) },
        suffix = { Text(suffix) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
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

/** Engine family card: logo or coloured monogram badge, name and tagline. */
@Composable
private fun EngineCard(family: EngineFamily, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    SelectableCard(selected, modifier, onClick) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val logo = family.logoRes
            if (logo != null) {
                Image(painterResource(logo), contentDescription = null, modifier = Modifier.size(40.dp).clip(CircleShape))
            } else {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color.hsl(family.hue, 0.45f, 0.42f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(family.monogram, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
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
private fun <T> Segmented(items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        items.forEachIndexed { i, item ->
            SegmentedButton(
                selected = item == selected,
                onClick = { onSelect(item) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = items.size),
                icon = {},
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

@Composable
private fun RangeLabels(start: String, end: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(start, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(end, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
