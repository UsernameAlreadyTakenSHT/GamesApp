package io.github.usernamealreadytakensht.games.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.unit.dp
import io.github.usernamealreadytakensht.games.R
import io.github.usernamealreadytakensht.games.game.SavedDraughtsGame
import io.github.usernamealreadytakensht.games.game.SavedGame
import io.github.usernamealreadytakensht.games.game.SavedMorrisGame

/** Main screen: pick a game. Saved games enable "Resume" on their card. */
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
) {
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
            Text(
                "Pick a game",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            GameCard(
                title = "Chess",
                subtitle = "Against Stockfish and more, offline",
                icon = R.drawable.piece_ln,
                onClick = onChess,
                extra = savedChess?.let { saved ->
                    {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "In progress · ${saved.config.opponentLabel} · " +
                                    "${saved.uciMoves.size} plies",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onResumeChess) { Text("Resume") }
                        }
                    }
                },
            )
            GameCard(
                title = "Draughts",
                subtitle = "International 10x10, against Scan or Moby Dam, offline",
                icon = R.drawable.stone_l2,
                onClick = onDraughts,
                extra = savedDraughts?.let { saved ->
                    {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "In progress · ${saved.config.opponentLabel} · ${saved.moves.size} plies",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onResumeDraughts) { Text("Resume") }
                        }
                    }
                },
            )
            GameCard(
                title = "Nine Men's Morris",
                subtitle = "Place, slide and mill, against Sanmill or Miller, offline",
                icon = R.drawable.ic_morris_board,
                onClick = onMorris,
                extra = savedMorris?.let { saved ->
                    {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "In progress · ${saved.config.opponentLabel} · ${saved.moves.size} plies",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = onResumeMorris) { Text("Resume") }
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun GameCard(
    title: String,
    subtitle: String,
    icon: Int,
    onClick: () -> Unit,
    extra: (@Composable () -> Unit)? = null,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Image(painterResource(icon), contentDescription = null, modifier = Modifier.size(56.dp))
                Column {
                    Text(title, style = MaterialTheme.typography.titleLarge)
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            extra?.invoke()
        }
    }
}
