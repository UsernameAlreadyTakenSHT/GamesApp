package io.github.usernamealreadytakensht.games.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.usernamealreadytakensht.games.R
import io.github.usernamealreadytakensht.games.game.SavedDraughtsGame
import io.github.usernamealreadytakensht.games.game.SavedGame
import io.github.usernamealreadytakensht.games.game.SavedMorrisGame
import io.github.usernamealreadytakensht.games.game.SavedShogiGame
import io.github.usernamealreadytakensht.games.game.SavedFoxGame
import io.github.usernamealreadytakensht.games.game.SavedTaflGame

/** One tile of the home grid. */
private class GameTile(val title: String, val icon: Int, val onOpen: () -> Unit, val resumes: List<Resume>) {
    constructor(title: String, icon: Int, resumable: Boolean, onOpen: () -> Unit, onResume: () -> Unit) :
        this(title, icon, onOpen, if (resumable) listOf(Resume("Resume", onResume)) else emptyList())
}

/** A "Resume" link on a tile: one per game in progress (draughts keeps one per rule set). */
private class Resume(val label: String, val onResume: () -> Unit)

/** Main screen: a two-column grid of games. A saved game adds a "Resume" link to its tile. */
@Composable
fun HomeScreen(
    savedChess: SavedGame?,
    savedDraughts: SavedDraughtsGame?,
    savedCheckers: SavedDraughtsGame?,
    onResumeCheckers: () -> Unit,
    onChess: () -> Unit,
    onResumeChess: () -> Unit,
    onDraughts: () -> Unit,
    onResumeDraughts: () -> Unit,
    savedMorris: SavedMorrisGame?,
    onMorris: () -> Unit,
    onResumeMorris: () -> Unit,
    savedTafl: SavedTaflGame?,
    onTafl: () -> Unit,
    onResumeTafl: () -> Unit,
    savedFox: SavedFoxGame?,
    onFox: () -> Unit,
    onResumeFox: () -> Unit,
    savedShogi: SavedShogiGame?,
    onShogi: () -> Unit,
    onResumeShogi: () -> Unit,
    onHistory: () -> Unit,
) {
    val tiles = listOf(
        GameTile("Chess", R.drawable.piece_ln, savedChess != null, onChess, onResumeChess),
        // Both rule sets can have a game in progress: each gets its own link, named by its board
        // when there are two ("Resume" twice would not fit the tile).
        GameTile(
            "Draughts", R.drawable.stone_l2, onDraughts,
            listOfNotNull(
                savedDraughts?.let { Resume(if (savedCheckers != null) "10\u00D710" else "Resume", onResumeDraughts) },
                savedCheckers?.let { Resume(if (savedDraughts != null) "8\u00D78" else "Resume", onResumeCheckers) },
            ),
        ),
        GameTile("Shogi", R.drawable.ic_shogi_board, savedShogi != null, onShogi, onResumeShogi),
        GameTile("Nine Men's Morris", R.drawable.ic_morris_board, savedMorris != null, onMorris, onResumeMorris),
        GameTile("Hnefatafl", R.drawable.ic_tafl_board, savedTafl != null, onTafl, onResumeTafl),
        GameTile("Fox games", R.drawable.ic_fox_board, savedFox != null, onFox, onResumeFox),
    )
    Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                // Split screen, a large display size: the grid scrolls instead of losing its last row.
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Games", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onHistory) { Text("History") }
            }
            Spacer(Modifier.height(4.dp))
            tiles.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(IntrinsicSize.Max)) {
                    row.forEach { tile -> GameCard(tile, Modifier.weight(1f).fillMaxHeight()) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun GameCard(tile: GameTile, modifier: Modifier) {
    Card(onClick = tile.onOpen, modifier = modifier) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 16.dp, start = 12.dp, end = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Image(painterResource(tile.icon), contentDescription = null, modifier = Modifier.size(64.dp))
                Text(tile.title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
            // The resume link sits over the top-right corner, so a saved game never resizes the tile.
            // One link sits in the top-right corner; two take a corner each, clear of the icon.
            Row(
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(6.dp),
                horizontalArrangement = if (tile.resumes.size > 1) Arrangement.SpaceBetween else Arrangement.End,
            ) {
                tile.resumes.forEach { resume ->
                    Surface(onClick = resume.onResume, shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary) {
                        Text(
                            resume.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
    }
}
