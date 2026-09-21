package io.github.usernamealreadytakensht.games.game

import com.github.bhlangonijr.chesslib.Side
import org.json.JSONObject

/**
 * Engine families offered at the first level of the opponent picker. `monogram` and `hue`
 * (0-360) drive the badge drawn on the family card; `tagline` is the one-line pitch.
 */
enum class EngineFamily(val label: String, val tagline: String, val monogram: String, val hue: Float) {
    STOCKFISH("Stockfish", "The reference. Adjustable Elo.", "SF", 210f),
    LC0("Leela Chess Zero", "Neural networks, positional style.", "Lc0", 150f),
    RECKLESS("Reckless", "Top-tier engine in Rust.", "Rk", 30f),
    PLENTY("PlentyChess", "Top-tier engine in C++.", "Pc", 280f),
    BERSERK("Berserk", "Top-tier engine in C.", "Bk", 0f),
    SUNFISH("Sunfish", "Tiny and beatable.", "Sf", 50f),
    MAIA("Maia", "Plays like a human of any level.", "Ma", 330f),
    RODENT("Rodent", "Seven personalities.", "Ro", 100f),
}

/** How the strength of an engine variant is adjusted. */
enum class StrengthKind {
    /** Stockfish `UCI_Elo`; null = unlimited. */
    ELO,
    /** Lc0 search node count per move; null = time-based (as strong as the clock allows). */
    NODES,
    /** Maia: the Elo picks which human-trained network is loaded (no search). */
    HUMAN_ELO,
    /** Fixed search depth per move (alpha-beta engines without a UCI_Elo option); null = time-based. */
    DEPTH,
}

/**
 * Engine variants: a UCI binary bundled in jniLibs, plus for Lc0 a network shipped in
 * `assets/nets`. `eloMin`/`eloMax` are the bounds of Stockfish's `UCI_Elo` option.
 */
enum class EngineKind(
    val family: EngineFamily,
    val label: String,
    val binary: String,
    val strength: StrengthKind,
    val description: String,
    val weights: String? = null,
    val eloMin: Int = 0,
    val eloMax: Int = 0,
    /** Asset folder extracted and used as the process working directory (engine data files). */
    val dataDir: String? = null,
    /** Rodent personality file, relative to [dataDir]. */
    val personality: String? = null,
) {
    STOCKFISH_19(
        EngineFamily.STOCKFISH, "Stockfish 19", "libstockfish.so", StrengthKind.ELO,
        "Current release, NNUE evaluation.", eloMin = 1320, eloMax = 3190,
    ),
    STOCKFISH_11(
        EngineFamily.STOCKFISH, "Stockfish 11", "libstockfish11.so", StrengthKind.ELO,
        "Last classical release (hand-crafted evaluation): sharper, more materialistic style.",
        eloMin = 1350, eloMax = 2850,
    ),
    LC0_BADGYAL(
        EngineFamily.LC0, "Bad Gyal 8", "liblc0.so", StrengthKind.NODES,
        "128x10 network. Strength is set by the number of search nodes per move.",
        weights = "badgyal-8.lc0",
    ),
    LC0_T1(
        EngineFamily.LC0, "T1 256x10", "liblc0.so", StrengthKind.NODES,
        "Official 256x10 distilled network: stronger than Bad Gyal per node, but ~6x slower on CPU.",
        weights = "t1-256x10.lc0",
    ),
    RECKLESS_09(
        EngineFamily.RECKLESS, "Reckless 0.9", "libreckless.so", StrengthKind.DEPTH,
        "Top-tier Rust engine (~3500 Elo). Strength is set by the search depth per move.",
    ),
    PLENTY_8(
        EngineFamily.PLENTY, "PlentyChess 8.0", "libplenty.so", StrengthKind.DEPTH,
        "Top-tier C++ engine (~3600 Elo). Strength is set by the search depth per move.",
    ),
    BERSERK_14(
        EngineFamily.BERSERK, "Berserk 14", "libberserk.so", StrengthKind.DEPTH,
        "Top-tier C engine (~3500 Elo). Strength is set by the search depth per move.",
    ),
    SUNFISH_2026(
        EngineFamily.SUNFISH, "Sunfish 2026", "", StrengthKind.DEPTH,
        "Tiny engine (Kotlin port of the Python original, piece-square tables only). Weak and " +
            "beatable: think club player at low depth.",
    ),
    MAIA3_5M(
        EngineFamily.MAIA, "Maia 3 (5M)", "", StrengthKind.HUMAN_ELO,
        "Transformer trained on human games, one model for every level: plays like a human of " +
            "the chosen rating, no search. Runs in-process with ONNX Runtime.",
        weights = "maia3-5m.onnx", eloMin = 600, eloMax = 2600,
    ),
    LC0_MAIA(
        EngineFamily.LC0, "Maia", "liblc0.so", StrengthKind.HUMAN_ELO,
        "Networks trained on human games of a given rating: plays (and blunders) like a human " +
            "of that level. No search.",
        eloMin = 1100, eloMax = 1900,
    ),
    RODENT_DEFAULT(
        EngineFamily.RODENT, "Rodent", "librodent.so", StrengthKind.ELO,
        "Rodent V's default personality: balanced NNUE play.",
        eloMin = 800, eloMax = 3000, dataDir = "rodent", personality = "personalities/rodent.txt",
    ),
    RODENT_TAL(
        EngineFamily.RODENT, "Tal", "librodent.so", StrengthKind.ELO,
        "Aggressive, sacrificial style on a dedicated network, with Tal's opening repertoire.",
        eloMin = 800, eloMax = 3000, dataDir = "rodent", personality = "personalities/tal_nnue.txt",
    ),
    RODENT_TAL_HYBRID(
        EngineFamily.RODENT, "Tal (hybrid)", "librodent.so", StrengthKind.ELO,
        "The attacking Tal profile on the standard network, mixed with hand-crafted terms.",
        eloMin = 800, eloMax = 3000, dataDir = "rodent", personality = "personalities/tal_hybrid.txt",
    ),
    RODENT_AMPERE(
        EngineFamily.RODENT, "Ampere", "librodent.so", StrengthKind.ELO,
        "Energetic, activity-driven personality.",
        eloMin = 800, eloMax = 3000, dataDir = "rodent", personality = "personalities/ampere.txt",
    ),
    RODENT_CHAOTIC(
        EngineFamily.RODENT, "Chaotic", "librodent.so", StrengthKind.ELO,
        "Unbalanced, tricky play with an offbeat opening book.",
        eloMin = 800, eloMax = 3000, dataDir = "rodent", personality = "personalities/chaotic.txt",
    ),
    RODENT_HECTOR(
        EngineFamily.RODENT, "Hector", "librodent.so", StrengthKind.ELO,
        "Hand-crafted evaluation only, no neural network: the classical Rodent feel.",
        eloMin = 800, eloMax = 3000, dataDir = "rodent", personality = "personalities/hector.txt",
    ),
    RODENT_NIMZOID(
        EngineFamily.RODENT, "Nimzoid", "librodent.so", StrengthKind.ELO,
        "Positional, prophylactic style in the spirit of Nimzowitsch, with a matching book.",
        eloMin = 800, eloMax = 3000, dataDir = "rodent", personality = "personalities/nimzoid.txt",
    );

    /** Network file for [strength]; Maia has one network per rating. */
    fun weightsFor(strength: Int?): String? =
        if (family == EngineFamily.LC0 && this.strength == StrengthKind.HUMAN_ELO) "maia-${strength ?: defaultStrength}.lc0" else weights

    /**
     * Strength presets offered by the UI; a trailing null means "Max" (Elo) or
     * "time-based" (nodes). Human Elo has no "Max".
     */
    val strengthPresets: List<Int?>
        get() = when (strength) {
            StrengthKind.ELO -> {
                val hundreds = ((eloMin / 100 + 1) * 100..(eloMax / 100) * 100 step 100).toList()
                listOf(eloMin) + hundreds.filter { it != eloMax } + listOf(eloMax, null)
            }
            StrengthKind.NODES -> NODE_PRESETS
            StrengthKind.HUMAN_ELO -> (eloMin..eloMax step 100).toList()
            StrengthKind.DEPTH -> DEPTH_PRESETS
        }

    /** Sensible starting strength when this variant is first selected. */
    val defaultStrength: Int?
        get() = when (strength) {
            StrengthKind.ELO -> snap(1500)
            StrengthKind.NODES -> 64
            StrengthKind.HUMAN_ELO -> 1500
            StrengthKind.DEPTH -> 5
        }

    /**
     * Carries a strength over from [from]: snapped when both use the same kind of setting,
     * otherwise reset to [defaultStrength].
     */
    fun carryOver(value: Int?, from: EngineKind): Int? =
        if (from.strength == strength) snap(value) else defaultStrength

    /** Snaps [value] to the closest preset of this variant (null stays null). */
    fun snap(value: Int?): Int? {
        val presets = strengthPresets.filterNotNull()
        if (presets.isEmpty()) return null
        // null means "Max" where a Max preset exists; otherwise fall back to the default.
        if (value == null) return if (null in strengthPresets) null else defaultStrength
        return presets.minBy { kotlin.math.abs(it - value) }
    }

    /** Plain-language feel of [value] for this variant, shown under the strength value. */
    fun levelDescription(value: Int?): String = when (strength) {
        StrengthKind.ELO, StrengthKind.HUMAN_ELO -> when {
            value == null -> "Full strength, unbeatable"
            value < 1000 -> "Beginner"
            value < 1400 -> "Casual player"
            value < 1800 -> "Club player"
            value < 2200 -> "Strong club player"
            value < 2500 -> "Expert"
            else -> "Master level"
        }
        StrengthKind.DEPTH -> if (family == EngineFamily.SUNFISH) when {
            value == null -> "As strong as it gets (~2000)"
            value <= 2 -> "Beginner: hangs pieces"
            value <= 4 -> "Casual player"
            value <= 6 -> "Club player"
            else -> "Strong club player"
        } else when {
            value == null -> "Full strength, unbeatable"
            value <= 2 -> "Solid but blind to 2-move tactics (~1600+)"
            value <= 5 -> "Strong club player"
            value <= 10 -> "Expert"
            else -> "Master level and beyond"
        }
        StrengthKind.NODES -> when {
            value == null -> "Full strength (time-based)"
            value == 1 -> "Network intuition only"
            value <= 16 -> "Light search"
            value <= 256 -> "Solid"
            else -> "Strong"
        }
    }

    fun strengthLabel(value: Int?): String = when (strength) {
        StrengthKind.ELO -> if (value == null) "Max" else "Elo $value"
        StrengthKind.NODES -> if (value == null) "Max" else "$value node${if (value > 1) "s" else ""}"
        StrengthKind.HUMAN_ELO -> "Elo ${value ?: defaultStrength}"
        StrengthKind.DEPTH -> if (value == null) "Max" else "Depth $value"
    }

    companion object {
        val NODE_PRESETS: List<Int?> = listOf(1, 4, 16, 64, 256, 1000, null)
        val DEPTH_PRESETS: List<Int?> = listOf(1, 2, 3, 4, 5, 6, 8, 10, 12, 15, 20, null)

        fun of(family: EngineFamily): List<EngineKind> = entries.filter { it.family == family }
    }
}

/** Time control. All durations are in milliseconds. */
sealed class TimeControl {
    /** No clock. */
    data object None : TimeControl()

    /** Sudden death: a fixed budget for the whole game. */
    data class SuddenDeath(val initialMs: Long) : TimeControl()

    /** Fischer: initial time plus an increment added after every move. */
    data class Fischer(val initialMs: Long, val incrementMs: Long) : TimeControl()

    /** Fixed time per move, reset after every move. */
    data class PerMove(val perMoveMs: Long) : TimeControl()

    /** Time each side starts with, or null without a clock. */
    val startingMs: Long?
        get() = when (this) {
            None -> null
            is SuddenDeath -> initialMs
            is Fischer -> initialMs
            is PerMove -> perMoveMs
        }

    val label: String
        get() = when (this) {
            None -> "No clock"
            is SuddenDeath -> "${initialMs / 60_000} min"
            is Fischer -> "${initialMs / 60_000} + ${incrementMs / 1000}"
            is PerMove -> "${perMoveMs / 1000} s / move"
        }

    fun toJson(): JSONObject = JSONObject().apply {
        when (this@TimeControl) {
            None -> put("type", "none")
            is SuddenDeath -> put("type", "sudden").put("initial", initialMs)
            is Fischer -> put("type", "fischer").put("initial", initialMs).put("inc", incrementMs)
            is PerMove -> put("type", "perMove").put("perMove", perMoveMs)
        }
    }

    companion object {
        fun fromJson(o: JSONObject): TimeControl = when (o.optString("type")) {
            "sudden" -> SuddenDeath(o.getLong("initial"))
            "fischer" -> Fischer(o.getLong("initial"), o.getLong("inc"))
            "perMove" -> PerMove(o.getLong("perMove"))
            else -> None
        }
    }
}

/** How many moves the player may take back during a game. */
enum class Takebacks(val label: String, val limit: Int?) {
    UNLIMITED("Unlimited", null),
    ONCE("Once", 1),
    OFF("Off", 0),
}

/** Multiplier on the engine's thinking time per move. */
enum class ThinkingTime(val label: String, val factor: Double) {
    FAST("Fast", 0.5),
    NORMAL("Normal", 1.0),
    SLOW("Slow", 2.0),
}

/**
 * Settings of a chess game.
 * `strength` is an Elo or a node count depending on the engine (see [EngineKind.strength]);
 * null = maximum. `playerSide` null = random.
 */
data class GameConfig(
    val engine: EngineKind = EngineKind.STOCKFISH_19,
    val strength: Int? = 1500,
    val playerSide: Side? = Side.WHITE,
    val timeControl: TimeControl = TimeControl.None,
    val takebacks: Takebacks = Takebacks.UNLIMITED,
    val thinking: ThinkingTime = ThinkingTime.NORMAL,
) {
    val strengthLabel: String get() = engine.strengthLabel(strength)

    /** "Stockfish 19 · Elo 1500", "Bad Gyal 8 · 64 nodes", "Maia 1500". */
    val opponentLabel: String
        get() = if (strengthLabel.isEmpty()) engine.label else "${engine.label} · $strengthLabel"

    fun toJson(): JSONObject = JSONObject().apply {
        put("engine", engine.name)
        put("strength", strength ?: -1)
        put("side", playerSide?.name ?: "random")
        put("clock", timeControl.toJson())
        put("takebacks", takebacks.name)
        put("thinking", thinking.name)
    }

    companion object {
        fun fromJson(o: JSONObject): GameConfig {
            val engine = runCatching { EngineKind.valueOf(o.getString("engine")) }
                .getOrDefault(EngineKind.STOCKFISH_19)
            // "elo" is the key used by earlier versions.
            val raw = if (o.has("strength")) o.optInt("strength", -1) else o.optInt("elo", 1500)
            return GameConfig(
                engine = engine,
                strength = engine.snap(raw.takeIf { it >= 0 }),
                playerSide = when (o.optString("side")) {
                    "WHITE" -> Side.WHITE
                    "BLACK" -> Side.BLACK
                    else -> null
                },
                timeControl = TimeControl.fromJson(o.optJSONObject("clock") ?: JSONObject()),
                takebacks = runCatching { Takebacks.valueOf(o.getString("takebacks")) }.getOrDefault(Takebacks.UNLIMITED),
                thinking = runCatching { ThinkingTime.valueOf(o.getString("thinking")) }.getOrDefault(ThinkingTime.NORMAL),
            )
        }
    }
}
