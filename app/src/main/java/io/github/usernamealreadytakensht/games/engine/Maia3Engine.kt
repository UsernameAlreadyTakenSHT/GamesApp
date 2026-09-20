package io.github.usernamealreadytakensht.games.engine

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import com.github.bhlangonijr.chesslib.Piece
import io.github.usernamealreadytakensht.games.game.EngineKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.exp
import kotlin.random.Random

/**
 * In-process opponent running a Maia-3 model (github.com/CSSLab/maia3) with ONNX Runtime.
 *
 * Maia-3 predicts the move a human of a given rating would play; there is no search. Like
 * the reference UCI wrapper, the move is *sampled* from the policy over legal moves
 * (temperature 1), so the same position does not always get the same answer.
 */
class Maia3Engine(private val context: Context, override val kind: EngineKind) : ChessEngine {

    override val weights: String? = kind.weights
    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null
    private val random = Random.Default

    override val isRunning: Boolean get() = session != null

    override suspend fun start(): Unit = withContext(Dispatchers.IO) {
        if (session != null) return@withContext
        val file = NetAssets.ensure(context, kind.weights ?: error("${kind.label} has no model file"))
        val e = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads((Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4))
        }
        session = e.createSession(file.absolutePath, opts)
        env = e
    }

    /** The rating to imitate is passed per query, so "configuring" just stores it. */
    override suspend fun configure(strength: Int?) {
        currentElo = strength ?: kind.defaultStrength ?: 1500
    }

    override suspend fun newGame() = Unit

    override suspend fun bestMove(moves: List<String>, moveTimeMs: Int, nodes: Int?, depth: Int?): String? =
        withContext(Dispatchers.Default) {
            val s = session ?: error("${kind.label} not started")
            val e = env!!
            val (board, tokens) = Maia3Encoder.encode(moves)
            val legal = Maia3Encoder.legalMoves(board)
            if (legal.isEmpty()) return@withContext null

            val elo = longArrayOf(currentElo.toLong())
            val inputs = mapOf(
                "tokens" to OnnxTensor.createTensor(e, FloatBuffer.wrap(tokens), longArrayOf(1, 64, Maia3Encoder.TOKEN_DIM.toLong())),
                "self_elo" to OnnxTensor.createTensor(e, LongBuffer.wrap(elo), longArrayOf(1)),
                "oppo_elo" to OnnxTensor.createTensor(e, LongBuffer.wrap(elo), longArrayOf(1)),
            )
            val logits = try {
                s.run(inputs).use { (it[0].value as Array<FloatArray>)[0] }
            } finally {
                inputs.values.forEach { it.close() }
            }

            // Softmax over legal moves, then sample (temperature 1, no nucleus cut).
            val indices = legal.keys.toIntArray()
            val max = indices.maxOf { logits[it] }
            val probs = DoubleArray(indices.size) { exp((logits[indices[it]] - max).toDouble()) }
            val sum = probs.sum()
            var r = random.nextDouble() * sum
            var pick = indices.size - 1
            for (i in probs.indices) {
                r -= probs[i]
                if (r <= 0) { pick = i; break }
            }
            val chosen = legal.getValue(indices[pick])

            fun uci(m: com.github.bhlangonijr.chesslib.move.Move): String {
                val promo = if (m.promotion != Piece.NONE) m.promotion.pieceType.sanSymbol.lowercase() else ""
                return m.from.value().lowercase() + m.to.value().lowercase() + promo
            }
            val top = probs.indices.sortedByDescending { probs[it] }.take(3)
                .joinToString { i -> "${uci(legal.getValue(indices[i]))} ${"%.2f".format(probs[i] / sum)}" }
            Log.d(TAG, "elo=${elo[0]} top: $top -> ${uci(chosen)}")
            uci(chosen)
        }

    @Volatile private var currentElo: Int = 1500

    override fun stop() = Unit

    override fun quit() {
        session?.close()
        session = null
    }

    companion object {
        private const val TAG = "Maia3"
    }
}
