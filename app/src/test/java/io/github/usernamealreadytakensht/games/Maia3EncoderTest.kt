package io.github.usernamealreadytakensht.games

import io.github.usernamealreadytakensht.games.engine.Maia3Encoder
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Checks the Kotlin Maia-3 encoding against vectors produced by the Python reference
 * (maia3/export_onnx.py). The model itself is validated against PyTorch by that script.
 */
class Maia3EncoderTest {

    private val root = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "maia3/reference.json").exists() }
    private val reference = JSONObject(File(root, "maia3/reference.json").readText())

    private fun caseMoves(c: JSONObject): List<String> {
        val arr = c.getJSONArray("moves")
        return List(arr.length()) { arr.getString(it) }
    }

    @Test
    fun tokensMatchPython() {
        val cases = reference.getJSONArray("cases")
        for (k in 0 until cases.length()) {
            val c = cases.getJSONObject(k)
            val moves = caseMoves(c)
            val expected = c.getJSONArray("tokens").let { a -> FloatArray(a.length()) { a.getInt(it).toFloat() } }
            val (_, actual) = Maia3Encoder.encode(moves)
            assertArrayEquals("tokens differ for $moves", expected, actual, 0f)
        }
    }

    @Test
    fun vocabularyRoundTrips() {
        for (i in 0 until Maia3Encoder.VOCAB) {
            assertEquals(i, Maia3Encoder.indexOf(Maia3Encoder.moveAt(i)))
        }
        assertEquals("e7e5", Maia3Encoder.mirrorMove("e2e4"))
        assertEquals("b2a1q", Maia3Encoder.mirrorMove("b7a8q"))
    }

    /** Same softmax-over-legal-moves selection as Maia3Engine, on reference logits shape. */
    @Test
    fun legalMoveIndicesCoverReferenceTopMoves() {
        val cases = reference.getJSONArray("cases")
        for (k in 0 until cases.length()) {
            val c = cases.getJSONObject(k)
            val (board, _) = Maia3Encoder.encode(caseMoves(c))
            val legal = Maia3Encoder.legalMoves(board)
            val top = c.getJSONArray("top")
            for (t in 0 until top.length()) {
                val idx = top.getJSONObject(t).getInt("index")
                assertTrue("reference move index $idx must be legal", idx in legal)
            }
        }
    }
}
