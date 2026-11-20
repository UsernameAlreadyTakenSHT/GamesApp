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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.usernamealreadytakensht.games.engine.NetAssets
import io.github.usernamealreadytakensht.games.game.GameConfig
import io.github.usernamealreadytakensht.games.game.GameRepository
import io.github.usernamealreadytakensht.games.game.draughts.DraughtsConfig
import io.github.usernamealreadytakensht.games.game.draughts.DraughtsVariant
import io.github.usernamealreadytakensht.games.game.fox.FoxConfig
import io.github.usernamealreadytakensht.games.game.morris.MorrisConfig
import io.github.usernamealreadytakensht.games.game.shogi.ShogiConfig
import io.github.usernamealreadytakensht.games.game.tafl.TaflConfig
import io.github.usernamealreadytakensht.games.ui.ChessLaunch
import io.github.usernamealreadytakensht.games.ui.draughts.DraughtsLaunch
import io.github.usernamealreadytakensht.games.ui.draughts.CheckersScreen
import io.github.usernamealreadytakensht.games.ui.draughts.DraughtsScreen
import io.github.usernamealreadytakensht.games.ui.draughts.DraughtsGameSetupScreen
import io.github.usernamealreadytakensht.games.ui.draughts.DraughtsOpponentSetupScreen
import io.github.usernamealreadytakensht.games.ui.ChessScreen
import io.github.usernamealreadytakensht.games.ui.GameSetupScreen
import io.github.usernamealreadytakensht.games.ui.OpponentSetupScreen
import io.github.usernamealreadytakensht.games.ui.HistoryScreen
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
import org.json.JSONObject

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

internal sealed interface Screen {
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
    data object History : Screen
}

/**
 * The screen as it is kept across a recreation of the activity (the process may have died
 * meanwhile). A game screen is kept as "resume the saved game", never as its new-game launch:
 * restoring that one would start the game again over the save.
 */
internal fun screenKey(screen: Screen): String = when (screen) {
    is Screen.ChessGame -> "ChessGame"
    is Screen.DraughtsGame -> "DraughtsGame:" + when (val l = screen.launch) {
        is DraughtsLaunch.NewGame -> l.config.variant.name
        is DraughtsLaunch.Resume -> l.variant.name
    }
    is Screen.MorrisGame -> "MorrisGame"
    is Screen.TaflGame -> "TaflGame"
    is Screen.FoxGame -> "FoxGame"
    is Screen.ShogiGame -> "ShogiGame"
    Screen.Home -> "Home"
    Screen.GameSetup -> "GameSetup"
    Screen.OpponentSetup -> "OpponentSetup"
    Screen.DraughtsSetup -> "DraughtsSetup"
    Screen.DraughtsOpponentSetup -> "DraughtsOpponentSetup"
    Screen.MorrisSetup -> "MorrisSetup"
    Screen.MorrisOpponentSetup -> "MorrisOpponentSetup"
    Screen.TaflSetup -> "TaflSetup"
    Screen.TaflOpponentSetup -> "TaflOpponentSetup"
    Screen.FoxSetup -> "FoxSetup"
    Screen.FoxOpponentSetup -> "FoxOpponentSetup"
    Screen.ShogiSetup -> "ShogiSetup"
    Screen.ShogiOpponentSetup -> "ShogiOpponentSetup"
    Screen.History -> "History"
}

/** The screen for a [screenKey]; the home screen for a key this version does not know. */
internal fun screenOf(key: String): Screen = when (key.substringBefore(':')) {
    "GameSetup" -> Screen.GameSetup
    "OpponentSetup" -> Screen.OpponentSetup
    "ChessGame" -> Screen.ChessGame(ChessLaunch.Resume)
    "DraughtsSetup" -> Screen.DraughtsSetup
    "DraughtsOpponentSetup" -> Screen.DraughtsOpponentSetup
    "DraughtsGame" -> Screen.DraughtsGame(
        DraughtsLaunch.Resume(
            runCatching { DraughtsVariant.valueOf(key.substringAfter(':')) }.getOrDefault(DraughtsVariant.INTERNATIONAL),
        ),
    )
    "MorrisSetup" -> Screen.MorrisSetup
    "MorrisOpponentSetup" -> Screen.MorrisOpponentSetup
    "MorrisGame" -> Screen.MorrisGame(MorrisLaunch.Resume)
    "TaflSetup" -> Screen.TaflSetup
    "TaflOpponentSetup" -> Screen.TaflOpponentSetup
    "TaflGame" -> Screen.TaflGame(TaflLaunch.Resume)
    "FoxSetup" -> Screen.FoxSetup
    "FoxOpponentSetup" -> Screen.FoxOpponentSetup
    "FoxGame" -> Screen.FoxGame(FoxLaunch.Resume)
    "ShogiSetup" -> Screen.ShogiSetup
    "ShogiOpponentSetup" -> Screen.ShogiOpponentSetup
    "ShogiGame" -> Screen.ShogiGame(ShogiLaunch.Resume)
    "History" -> Screen.History
    else -> Screen.Home
}

private val ScreenSaver = Saver<Screen, String>(save = { screenKey(it) }, restore = { screenOf(it) })

/** Keeps the settings being edited on the setup screens, in the JSON form they are stored in. */
private fun <T : Any> jsonSaver(toJson: (T) -> JSONObject, fromJson: (JSONObject) -> T) =
    Saver<T, String>(save = { toJson(it).toString() }, restore = { fromJson(JSONObject(it)) })

/** Minimal navigation: home → game setup → opponent setup → game. */
@Composable
private fun App() {
    val context = LocalContext.current
    val repo = remember { GameRepository(context) }
    // Saved with the activity's state: the screen and the settings being edited come back
    // after the system recreated the activity or killed the process in the background.
    var screen by rememberSaveable(stateSaver = ScreenSaver) { mutableStateOf<Screen>(Screen.Home) }
    var gameCounter by rememberSaveable { mutableIntStateOf(0) }
    // Settings being edited across the two setup screens; seeded from the last game played.
    var draft by rememberSaveable(stateSaver = jsonSaver(GameConfig::toJson, GameConfig.Companion::fromJson)) {
        mutableStateOf(repo.loadLastConfig())
    }
    var draughtsDraft by rememberSaveable(stateSaver = jsonSaver(DraughtsConfig::toJson, DraughtsConfig.Companion::fromJson)) {
        mutableStateOf(repo.loadLastDraughtsConfig())
    }
    var morrisDraft by rememberSaveable(stateSaver = jsonSaver(MorrisConfig::toJson, MorrisConfig.Companion::fromJson)) {
        mutableStateOf(repo.loadLastMorrisConfig())
    }
    var taflDraft by rememberSaveable(stateSaver = jsonSaver(TaflConfig::toJson, TaflConfig.Companion::fromJson)) {
        mutableStateOf(repo.loadLastTaflConfig())
    }
    var foxDraft by rememberSaveable(stateSaver = jsonSaver(FoxConfig::toJson, FoxConfig.Companion::fromJson)) {
        mutableStateOf(repo.loadLastFoxConfig())
    }
    var shogiDraft by rememberSaveable(stateSaver = jsonSaver(ShogiConfig::toJson, ShogiConfig.Companion::fromJson)) {
        mutableStateOf(repo.loadLastShogiConfig())
    }

    when (val s = screen) {
        Screen.Home -> HomeScreen(
            savedChess = repo.loadGame(),
            savedDraughts = repo.loadLatestDraughtsGame(),
            onChess = { draft = repo.loadLastConfig(); screen = Screen.GameSetup },
            onResumeChess = { screen = Screen.ChessGame(ChessLaunch.Resume) },
            onDraughts = { draughtsDraft = repo.loadLastDraughtsConfig(); screen = Screen.DraughtsSetup },
            // The rules of the game to resume are fixed here, at the tap: the latest save.
            onResumeDraughts = {
                val variant = repo.loadLatestDraughtsGame()?.config?.variant ?: DraughtsVariant.INTERNATIONAL
                screen = Screen.DraughtsGame(DraughtsLaunch.Resume(variant))
            },
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
            onHistory = { screen = Screen.History },
        )

        Screen.History -> {
            BackHandler { screen = Screen.Home }
            HistoryScreen(onBack = { screen = Screen.Home })
        }

        Screen.GameSetup -> {
            BackHandler { screen = Screen.Home }
            GameSetupScreen(
                config = draft,
                onChange = { draft = it },
                onBack = { screen = Screen.Home },
                onNext = {
                    if (draft.twoPlayers) {
                        repo.saveLastConfig(draft)
                        screen = Screen.ChessGame(ChessLaunch.NewGame(draft, ++gameCounter))
                    } else {
                        screen = Screen.OpponentSetup
                    }
                },
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
                onNext = {
                    if (draughtsDraft.twoPlayers) {
                        repo.saveLastDraughtsConfig(draughtsDraft)
                        screen = Screen.DraughtsGame(DraughtsLaunch.NewGame(draughtsDraft, ++gameCounter))
                    } else {
                        screen = Screen.DraughtsOpponentSetup
                    }
                },
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
            // A new game carries its rules; a resumed one those of the save picked on the home screen.
            val variant = when (val l = s.launch) {
                is DraughtsLaunch.NewGame -> l.config.variant
                is DraughtsLaunch.Resume -> l.variant
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
                onNext = {
                    if (morrisDraft.twoPlayers) {
                        repo.saveLastMorrisConfig(morrisDraft)
                        screen = Screen.MorrisGame(MorrisLaunch.NewGame(morrisDraft, ++gameCounter))
                    } else {
                        screen = Screen.MorrisOpponentSetup
                    }
                },
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
                onNext = {
                    if (taflDraft.twoPlayers) {
                        repo.saveLastTaflConfig(taflDraft)
                        screen = Screen.TaflGame(TaflLaunch.NewGame(taflDraft, ++gameCounter))
                    } else {
                        screen = Screen.TaflOpponentSetup
                    }
                },
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
                onNext = {
                    if (foxDraft.twoPlayers) {
                        repo.saveLastFoxConfig(foxDraft)
                        screen = Screen.FoxGame(FoxLaunch.NewGame(foxDraft, ++gameCounter))
                    } else {
                        screen = Screen.FoxOpponentSetup
                    }
                },
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
                onNext = {
                    if (shogiDraft.twoPlayers) {
                        repo.saveLastShogiConfig(shogiDraft)
                        screen = Screen.ShogiGame(ShogiLaunch.NewGame(shogiDraft, ++gameCounter))
                    } else {
                        screen = Screen.ShogiOpponentSetup
                    }
                },
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
