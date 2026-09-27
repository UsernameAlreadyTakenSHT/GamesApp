package io.github.usernamealreadytakensht.games

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.usernamealreadytakensht.games.engine.NetAssets
import io.github.usernamealreadytakensht.games.game.GameRepository
import io.github.usernamealreadytakensht.games.game.draughts.DraughtsVariant
import io.github.usernamealreadytakensht.games.ui.ChessLaunch
import io.github.usernamealreadytakensht.games.ui.draughts.DraughtsLaunch
import io.github.usernamealreadytakensht.games.ui.draughts.CheckersScreen
import io.github.usernamealreadytakensht.games.ui.draughts.DraughtsScreen
import io.github.usernamealreadytakensht.games.ui.draughts.DraughtsGameSetupScreen
import io.github.usernamealreadytakensht.games.ui.draughts.DraughtsOpponentSetupScreen
import io.github.usernamealreadytakensht.games.ui.ChessScreen
import io.github.usernamealreadytakensht.games.ui.GameSetupScreen
import io.github.usernamealreadytakensht.games.ui.OpponentSetupScreen
import io.github.usernamealreadytakensht.games.ui.HomeScreen
import io.github.usernamealreadytakensht.games.ui.morris.MorrisGameSetupScreen
import io.github.usernamealreadytakensht.games.ui.morris.MorrisLaunch
import io.github.usernamealreadytakensht.games.ui.morris.MorrisOpponentSetupScreen
import io.github.usernamealreadytakensht.games.ui.morris.MorrisScreen
import io.github.usernamealreadytakensht.games.ui.tafl.TaflGameSetupScreen
import io.github.usernamealreadytakensht.games.ui.tafl.TaflLaunch
import io.github.usernamealreadytakensht.games.ui.tafl.TaflOpponentSetupScreen
import io.github.usernamealreadytakensht.games.ui.tafl.TaflScreen
import io.github.usernamealreadytakensht.games.ui.fox.FoxGameSetupScreen
import io.github.usernamealreadytakensht.games.ui.fox.FoxLaunch
import io.github.usernamealreadytakensht.games.ui.fox.FoxOpponentSetupScreen
import io.github.usernamealreadytakensht.games.ui.fox.FoxScreen
import io.github.usernamealreadytakensht.games.ui.shogi.ShogiGameSetupScreen
import io.github.usernamealreadytakensht.games.ui.shogi.ShogiLaunch
import io.github.usernamealreadytakensht.games.ui.shogi.ShogiOpponentSetupScreen
import io.github.usernamealreadytakensht.games.ui.shogi.ShogiScreen
import io.github.usernamealreadytakensht.games.ui.theme.GamesTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideSystemBars()
        // Drop networks left behind by an older version (can be hundreds of MB).
        Thread { runCatching { NetAssets.pruneStale(applicationContext) } }.start()
        setContent {
            GamesTheme {
                // Full screen: only the camera cutout is kept clear.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .windowInsetsPadding(WindowInsets.displayCutout),
                ) {
                    App()
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Dialogs and the keyboard can bring the bars back; hide them again on return.
        if (hasFocus) hideSystemBars()
    }

    /** Immersive mode: status and navigation bars hidden, shown briefly by a swipe from the edge. */
    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
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
    data object MorrisSetup : Screen
    data object MorrisOpponentSetup : Screen
    data class MorrisGame(val launch: MorrisLaunch) : Screen
    data object TaflSetup : Screen
    data object TaflOpponentSetup : Screen
    data class TaflGame(val launch: TaflLaunch) : Screen
    data object FoxSetup : Screen
    data object FoxOpponentSetup : Screen
    data class FoxGame(val launch: FoxLaunch) : Screen
    data object ShogiSetup : Screen
    data object ShogiOpponentSetup : Screen
    data class ShogiGame(val launch: ShogiLaunch) : Screen
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
    var morrisDraft by remember { mutableStateOf(repo.loadLastMorrisConfig()) }
    var taflDraft by remember { mutableStateOf(repo.loadLastTaflConfig()) }
    var foxDraft by remember { mutableStateOf(repo.loadLastFoxConfig()) }
    var shogiDraft by remember { mutableStateOf(repo.loadLastShogiConfig()) }

    when (val s = screen) {
        Screen.Home -> HomeScreen(
            savedChess = repo.loadGame(),
            savedDraughts = repo.loadLatestDraughtsGame(),
            onChess = { draft = repo.loadLastConfig(); screen = Screen.GameSetup },
            onResumeChess = { screen = Screen.ChessGame(ChessLaunch.Resume) },
            onDraughts = { draughtsDraft = repo.loadLastDraughtsConfig(); screen = Screen.DraughtsSetup },
            onResumeDraughts = { screen = Screen.DraughtsGame(DraughtsLaunch.Resume) },
            savedMorris = repo.loadMorrisGame(),
            onMorris = { morrisDraft = repo.loadLastMorrisConfig(); screen = Screen.MorrisSetup },
            onResumeMorris = { screen = Screen.MorrisGame(MorrisLaunch.Resume) },
            savedTafl = repo.loadTaflGame(),
            onTafl = { taflDraft = repo.loadLastTaflConfig(); screen = Screen.TaflSetup },
            onResumeTafl = { screen = Screen.TaflGame(TaflLaunch.Resume) },
            savedFox = repo.loadFoxGame(),
            onFox = { foxDraft = repo.loadLastFoxConfig(); screen = Screen.FoxSetup },
            onResumeFox = { screen = Screen.FoxGame(FoxLaunch.Resume) },
            savedShogi = repo.loadShogiGame(),
            onShogi = { shogiDraft = repo.loadLastShogiConfig(); screen = Screen.ShogiSetup },
            onResumeShogi = { screen = Screen.ShogiGame(ShogiLaunch.Resume) },
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
            // A new game carries its rules; a resumed one takes them from the save.
            val variant = when (val l = s.launch) {
                is DraughtsLaunch.NewGame -> l.config.variant
                DraughtsLaunch.Resume -> repo.loadLatestDraughtsGame()?.config?.variant ?: DraughtsVariant.INTERNATIONAL
            }
            if (variant == DraughtsVariant.ENGLISH) {
                CheckersScreen(
                    launch = s.launch,
                    onBack = { screen = Screen.Home },
                    onNewGame = { screen = Screen.DraughtsSetup },
                )
            } else {
                DraughtsScreen(
                    launch = s.launch,
                    onBack = { screen = Screen.Home },
                    onNewGame = { screen = Screen.DraughtsSetup },
                )
            }
        }

        Screen.MorrisSetup -> {
            BackHandler { screen = Screen.Home }
            MorrisGameSetupScreen(
                config = morrisDraft,
                onChange = { morrisDraft = it },
                onBack = { screen = Screen.Home },
                onNext = { screen = Screen.MorrisOpponentSetup },
            )
        }

        Screen.MorrisOpponentSetup -> {
            BackHandler { screen = Screen.MorrisSetup }
            MorrisOpponentSetupScreen(
                config = morrisDraft,
                onChange = { morrisDraft = it },
                onBack = { screen = Screen.MorrisSetup },
                onPlay = {
                    repo.saveLastMorrisConfig(morrisDraft)
                    screen = Screen.MorrisGame(MorrisLaunch.NewGame(morrisDraft, ++gameCounter))
                },
            )
        }

        is Screen.MorrisGame -> {
            BackHandler { screen = Screen.Home }
            MorrisScreen(
                launch = s.launch,
                onBack = { screen = Screen.Home },
                onNewGame = { screen = Screen.MorrisSetup },
            )
        }

        Screen.TaflSetup -> {
            BackHandler { screen = Screen.Home }
            TaflGameSetupScreen(
                config = taflDraft,
                onChange = { taflDraft = it },
                onBack = { screen = Screen.Home },
                onNext = { screen = Screen.TaflOpponentSetup },
            )
        }

        Screen.TaflOpponentSetup -> {
            BackHandler { screen = Screen.TaflSetup }
            TaflOpponentSetupScreen(
                config = taflDraft,
                onChange = { taflDraft = it },
                onBack = { screen = Screen.TaflSetup },
                onPlay = {
                    repo.saveLastTaflConfig(taflDraft)
                    screen = Screen.TaflGame(TaflLaunch.NewGame(taflDraft, ++gameCounter))
                },
            )
        }

        is Screen.TaflGame -> {
            BackHandler { screen = Screen.Home }
            TaflScreen(
                launch = s.launch,
                onBack = { screen = Screen.Home },
                onNewGame = { screen = Screen.TaflSetup },
            )
        }

        Screen.FoxSetup -> {
            BackHandler { screen = Screen.Home }
            FoxGameSetupScreen(
                config = foxDraft,
                onChange = { foxDraft = it },
                onBack = { screen = Screen.Home },
                onNext = { screen = Screen.FoxOpponentSetup },
            )
        }

        Screen.FoxOpponentSetup -> {
            BackHandler { screen = Screen.FoxSetup }
            FoxOpponentSetupScreen(
                config = foxDraft,
                onChange = { foxDraft = it },
                onBack = { screen = Screen.FoxSetup },
                onPlay = {
                    repo.saveLastFoxConfig(foxDraft)
                    screen = Screen.FoxGame(FoxLaunch.NewGame(foxDraft, ++gameCounter))
                },
            )
        }

        is Screen.FoxGame -> {
            BackHandler { screen = Screen.Home }
            FoxScreen(
                launch = s.launch,
                onBack = { screen = Screen.Home },
                onNewGame = { screen = Screen.FoxSetup },
            )
        }

        Screen.ShogiSetup -> {
            BackHandler { screen = Screen.Home }
            ShogiGameSetupScreen(
                config = shogiDraft,
                onChange = { shogiDraft = it },
                onBack = { screen = Screen.Home },
                onNext = { screen = Screen.ShogiOpponentSetup },
            )
        }

        Screen.ShogiOpponentSetup -> {
            BackHandler { screen = Screen.ShogiSetup }
            ShogiOpponentSetupScreen(
                config = shogiDraft,
                onChange = { shogiDraft = it },
                onBack = { screen = Screen.ShogiSetup },
                onPlay = {
                    repo.saveLastShogiConfig(shogiDraft)
                    screen = Screen.ShogiGame(ShogiLaunch.NewGame(shogiDraft, ++gameCounter))
                },
            )
        }

        is Screen.ShogiGame -> {
            BackHandler { screen = Screen.Home }
            ShogiScreen(
                launch = s.launch,
                onBack = { screen = Screen.Home },
                onNewGame = { screen = Screen.ShogiSetup },
            )
        }
    }
}
