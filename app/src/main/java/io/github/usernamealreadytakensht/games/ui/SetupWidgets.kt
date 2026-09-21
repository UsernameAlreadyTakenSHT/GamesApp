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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import io.github.usernamealreadytakensht.games.game.TimeControl
import kotlin.math.roundToInt

/*
 * Building blocks shared by the chess and draughts setup screens, so that both games get the
 * same two-step "New game → Opponent" flow and look.
 */

// ---------------------------------------------------------------- frame

/** Common frame of the setup screens: back arrow, title, scrolling body, bottom action. */
@Composable
internal fun SetupScaffold(
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
internal fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
}

@Composable
internal fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ---------------------------------------------------------------- colour

/**
 * Three "Your colour" cards. [white] / [black] are the piece drawables of each side;
 * [selected] is true/false for a side, null for random.
 */
@Composable
internal fun ColorCards(white: Int, black: Int, selected: Boolean?, onSelect: (Boolean?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ColorCard("White", listOf(white), selected == true, Modifier.weight(1f)) { onSelect(true) }
        ColorCard("Black", listOf(black), selected == false, Modifier.weight(1f)) { onSelect(false) }
        ColorCard("Random", listOf(white, black), selected == null, Modifier.weight(1f)) { onSelect(null) }
    }
}

/** Selectable card showing one or two piece glyphs. */
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

// ---------------------------------------------------------------- clock

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

/** "Time control" section: kind selector on one line, then compact typed fields. */
@Composable
internal fun ClockSection(tc: TimeControl, onChange: (TimeControl) -> Unit) {
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

    SectionTitle("Time control")
    Segmented(items = ClockKind.entries, selected = tc.clockKind, label = { it.label }, onSelect = { onChange(clock(it)) })
    when (tc.clockKind) {
        ClockKind.NONE -> Hint("No time limit.")
        ClockKind.SUDDEN_DEATH -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(minutes, "min", Modifier.weight(1f)) { onChange(clock(ClockKind.SUDDEN_DEATH, m = it)) }
            Text("per player", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(2f))
        }
        ClockKind.FISCHER -> {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(minutes, "min", Modifier.weight(1f)) { onChange(clock(ClockKind.FISCHER, m = it)) }
                Text("+", style = MaterialTheme.typography.titleLarge)
                NumberField(increment, "s / move", Modifier.weight(1f)) { onChange(clock(ClockKind.FISCHER, i = it)) }
                Spacer(Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FISCHER_PRESETS.forEach { (label, mi) ->
                    val (m, i) = mi
                    FilterChip(
                        selected = minutes == m.toLong() && increment == i.toLong(),
                        onClick = { onChange(clock(ClockKind.FISCHER, m = m.toLong(), i = i.toLong())) },
                        label = { Text(label) },
                    )
                }
            }
        }
        ClockKind.PER_MOVE -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(perMove, "s", Modifier.weight(1f)) { onChange(clock(ClockKind.PER_MOVE, p = it)) }
            Text("per move, reset each move", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(2f))
        }
    }
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

// ---------------------------------------------------------------- opponent

/** Opponent card: a round badge (logo or monogram), name and tagline. */
@Composable
internal fun OpponentCard(
    label: String,
    tagline: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
    badge: @Composable () -> Unit,
) {
    SelectableCard(selected, modifier, onClick) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            badge()
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(tagline, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
        }
    }
}

/** 40 dp round badge: the [logo] drawable when there is one, else [monogram] on a coloured disc. */
@Composable
internal fun EngineBadge(logo: Int?, monogram: String, hue: Float) {
    if (logo != null) {
        Image(painterResource(logo), contentDescription = null, modifier = Modifier.size(40.dp).clip(CircleShape))
    } else {
        Box(
            modifier = Modifier.size(40.dp).background(Color.hsl(hue, 0.45f, 0.42f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(monogram, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * "Strength" card: a big value with its unit, a one-line description, and a slider over
 * [presets] (the current index is [index]).
 */
@Composable
internal fun StrengthCard(
    big: String,
    unit: String,
    description: String,
    presets: Int,
    index: Int,
    rangeStart: String,
    rangeEnd: String,
    onIndex: (Int) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(big, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Text(
                    unit,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Slider(
                value = index.toFloat(),
                onValueChange = { onIndex(it.roundToInt().coerceIn(0, presets - 1)) },
                valueRange = 0f..(presets - 1).toFloat(),
                steps = presets - 2,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(rangeStart, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(rangeEnd, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ---------------------------------------------------------------- generic controls

@Composable
internal fun SelectableCard(selected: Boolean, modifier: Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
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
internal fun <T> Segmented(items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
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
internal fun <T> ChipFlow(items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            FilterChip(selected = item == selected, onClick = { onSelect(item) }, label = { Text(label(item)) })
        }
    }
}
