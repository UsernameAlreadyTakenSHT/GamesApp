package io.github.usernamealreadytakensht.games.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import io.github.usernamealreadytakensht.games.game.SavedFoxGame
import io.github.usernamealreadytakensht.games.game.SavedTaflGame

/** One tile of the home grid. */
private class GameTile(val title: String, val icon: Int, val resumable: Boolean, val onOpen: () -> Unit, val onResume: () -> Unit)

/** Main screen: a two-column grid of games. A saved game adds a "Resume" link to its tile. */
@Composable
fun HomeScreen(
    savedChess: SavedGame?,
    savedDraughts: SavedDraughtsGame?,
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
) {
    val tiles = listOf(
        GameTile("Chess", R.drawable.piece_ln, savedChess != null, onChess, onResumeChess),
        GameTile("Draughts", R.drawable.stone_l2, savedDraughts != null, onDraughts, onResumeDraughts),
        GameTile("Nine Men's Morris", R.drawable.ic_morris_board, savedMorris != null, onMorris, onResumeMorris),
        GameTile("Hnefatafl", R.drawable.ic_tafl_board, savedTafl != null, onTafl, onResumeTafl),
        GameTile("Fox games", R.drawable.ic_fox_board, savedFox != null, onFox, onResumeFox),
    )
    Scaffold(modifier = Modifier.fillMaxSize()) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Text("Games", style = MaterialTheme.typography.headlineMedium)
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
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp, start = 12.dp, end = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Image(painterResource(tile.icon), contentDescription = null, modifier = Modifier.size(64.dp))
            Text(tile.title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            if (tile.resumable) {
                TextButton(onClick = tile.onResume) { Text("Resume") }
            } else {
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}
