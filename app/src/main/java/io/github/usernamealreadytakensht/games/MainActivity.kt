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
import io.github.usernamealreadytakensht.games.ui.CheckersScreen
import io.github.usernamealreadytakensht.games.ui.ChessLaunch
import io.github.usernamealreadytakensht.games.ui.ChessScreen
import io.github.usernamealreadytakensht.games.ui.ChessSetupScreen
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
    data object ChessSetup : Screen
    data class ChessGame(val launch: ChessLaunch) : Screen
    data object Checkers : Screen
}

/** Minimal navigation: home → setup → game. */
@Composable
private fun App() {
    val context = LocalContext.current
    val repo = remember { GameRepository(context) }
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var gameCounter by remember { mutableIntStateOf(0) }

    when (val s = screen) {
        Screen.Home -> HomeScreen(
            savedChess = repo.loadGame(),
            onChess = { screen = Screen.ChessSetup },
            onResumeChess = { screen = Screen.ChessGame(ChessLaunch.Resume) },
            onCheckers = { screen = Screen.Checkers },
        )

        Screen.ChessSetup -> {
            BackHandler { screen = Screen.Home }
            ChessSetupScreen(
                initial = repo.loadLastConfig(),
                onBack = { screen = Screen.Home },
                onPlay = { config ->
                    repo.saveLastConfig(config)
                    screen = Screen.ChessGame(ChessLaunch.NewGame(config, ++gameCounter))
                },
            )
        }

        is Screen.ChessGame -> {
            BackHandler { screen = Screen.Home }
            ChessScreen(launch = s.launch, onBack = { screen = Screen.Home })
        }

        Screen.Checkers -> {
            BackHandler { screen = Screen.Home }
            CheckersScreen(onBack = { screen = Screen.Home })
        }
    }
}
