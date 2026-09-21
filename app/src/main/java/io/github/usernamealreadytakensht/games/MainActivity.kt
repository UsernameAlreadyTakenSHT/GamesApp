package io.github.usernamealreadytakensht.games

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import io.github.usernamealreadytakensht.games.game.GameRepository
import io.github.usernamealreadytakensht.games.ui.ChessLaunch
import io.github.usernamealreadytakensht.games.ui.draughts.DraughtsLaunch
import io.github.usernamealreadytakensht.games.ui.draughts.DraughtsScreen
import io.github.usernamealreadytakensht.games.ui.draughts.DraughtsGameSetupScreen
import io.github.usernamealreadytakensht.games.ui.draughts.DraughtsOpponentSetupScreen
import io.github.usernamealreadytakensht.games.ui.ChessScreen
import io.github.usernamealreadytakensht.games.ui.GameSetupScreen
import io.github.usernamealreadytakensht.games.ui.OpponentSetupScreen
import io.github.usernamealreadytakensht.games.ui.HomeScreen
import io.github.usernamealreadytakensht.games.ui.theme.GamesTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GamesTheme {
                App()
            }
        }
    }
}

private sealed interface Screen {
    data object Home : Screen
    data object GameSetup : Screen
    data object OpponentSetup : Screen
    data class ChessGame(val launch: ChessLaunch) : Screen
    data object DraughtsSetup : Screen
    data object DraughtsOpponentSetup : Screen
    data class DraughtsGame(val launch: DraughtsLaunch) : Screen
}

/** Minimal navigation: home → game setup → opponent setup → game. */
@Composable
private fun App() {
    val context = LocalContext.current
    val repo = remember { GameRepository(context) }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var gameCounter by remember { mutableIntStateOf(0) }
    // Settings being edited across the two setup screens; seeded from the last game played.
    var draft by remember { mutableStateOf(repo.loadLastConfig()) }
    var draughtsDraft by remember { mutableStateOf(repo.loadLastDraughtsConfig()) }

    when (val s = screen) {
        Screen.Home -> HomeScreen(
            savedChess = repo.loadGame(),
            savedDraughts = repo.loadDraughtsGame(),
            onChess = { draft = repo.loadLastConfig(); screen = Screen.GameSetup },
            onResumeChess = { screen = Screen.ChessGame(ChessLaunch.Resume) },
            onDraughts = { draughtsDraft = repo.loadLastDraughtsConfig(); screen = Screen.DraughtsSetup },
            onResumeDraughts = { screen = Screen.DraughtsGame(DraughtsLaunch.Resume) },
        )

        Screen.GameSetup -> {
            BackHandler { screen = Screen.Home }
            GameSetupScreen(
                config = draft,
                onChange = { draft = it },
                onBack = { screen = Screen.Home },
                onNext = { screen = Screen.OpponentSetup },
            )
        }

        Screen.OpponentSetup -> {
            BackHandler { screen = Screen.GameSetup }
            OpponentSetupScreen(
                config = draft,
                onChange = { draft = it },
                onBack = { screen = Screen.GameSetup },
                onPlay = {
                    repo.saveLastConfig(draft)
                    screen = Screen.ChessGame(ChessLaunch.NewGame(draft, ++gameCounter))
                },
            )
        }

        is Screen.ChessGame -> {
            BackHandler { screen = Screen.Home }
            ChessScreen(
                launch = s.launch,
                onBack = { screen = Screen.Home },
                onNewGame = { screen = Screen.GameSetup },
            )
        }

        Screen.DraughtsSetup -> {
            BackHandler { screen = Screen.Home }
            DraughtsGameSetupScreen(
                config = draughtsDraft,
                onChange = { draughtsDraft = it },
                onBack = { screen = Screen.Home },
                onNext = { screen = Screen.DraughtsOpponentSetup },
            )
        }

        Screen.DraughtsOpponentSetup -> {
            BackHandler { screen = Screen.DraughtsSetup }
            DraughtsOpponentSetupScreen(
                config = draughtsDraft,
                onChange = { draughtsDraft = it },
                onBack = { screen = Screen.DraughtsSetup },
                onPlay = {
                    repo.saveLastDraughtsConfig(draughtsDraft)
                    screen = Screen.DraughtsGame(DraughtsLaunch.NewGame(draughtsDraft, ++gameCounter))
                },
            )
        }

        is Screen.DraughtsGame -> {
            BackHandler { screen = Screen.Home }
            DraughtsScreen(
                launch = s.launch,
                onBack = { screen = Screen.Home },
                onNewGame = { screen = Screen.DraughtsSetup },
            )
        }
    }
}
