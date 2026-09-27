package io.github.usernamealreadytakensht.games.ui.shogi

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import io.github.usernamealreadytakensht.games.game.shogi.Shogi
import io.github.usernamealreadytakensht.games.game.shogi.Shogi.Kind
import io.github.usernamealreadytakensht.games.game.shogi.ShogiPieceStyle

private val PieceFace = Color(0xFFF3DDA8)
private val PieceEdge = Color(0xFF7A5A2E)
private val Ink = Color(0xFF1B1B1B)
private val PromotedInk = Color(0xFFC62828)

/** Kanji for a piece (Gote's king is 王, Sente's 玉, as on a real set). */
private fun kanji(kind: Kind, promoted: Boolean, side: Shogi.Side): String = when (kind) {
    Kind.KING -> if (side == Shogi.Side.GOTE) "王" else "玉"
    Kind.ROOK -> if (promoted) "龍" else "飛"
    Kind.BISHOP -> if (promoted) "馬" else "角"
    Kind.GOLD -> "金"
    Kind.SILVER -> if (promoted) "全" else "銀"
    Kind.KNIGHT -> if (promoted) "圭" else "桂"
    Kind.LANCE -> if (promoted) "杏" else "香"
    Kind.PAWN -> if (promoted) "と" else "歩"
}

/** Relative size: pieces grow with rank, as on a real set. */
private fun scale(kind: Kind): Float = when (kind) {
    Kind.KING -> 1f
    Kind.ROOK, Kind.BISHOP -> 0.96f
    Kind.GOLD, Kind.SILVER -> 0.92f
    Kind.KNIGHT -> 0.88f
    Kind.LANCE -> 0.86f
    Kind.PAWN -> 0.82f
}

/**
 * A shogi piece: a wooden pentagon pointing at the opponent, with its kanji (or letter)
 * written on it, red when promoted. [upright] = pointing up the screen.
 */
@Composable
fun ShogiPiece(
    kind: Kind,
    promoted: Boolean,
    side: Shogi.Side,
    upright: Boolean,
    style: ShogiPieceStyle,
    cell: Dp,
    modifier: Modifier = Modifier,
) {
    val s = scale(kind)
    val density = LocalDensity.current
    val label = when (style) {
        ShogiPieceStyle.KANJI -> kanji(kind, promoted, side)
        ShogiPieceStyle.LETTERS -> (if (promoted) "+" else "") + kind.letter
    }
    val fontPx = with(density) { (cell * s * if (style == ShogiPieceStyle.KANJI) 0.52f else 0.44f).toSp() }
    Box(modifier = modifier.fillMaxSize().rotate(if (upright) 0f else 180f), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width * s
            val h = size.height * s
            val left = (size.width - w) / 2
            val top = (size.height - h) / 2 + size.height * 0.02f
            val path = Path().apply {
                moveTo(left + w * 0.5f, top + h * 0.04f)
                lineTo(left + w * 0.84f, top + h * 0.2f)
                lineTo(left + w * 0.94f, top + h * 0.96f)
                lineTo(left + w * 0.06f, top + h * 0.96f)
                lineTo(left + w * 0.16f, top + h * 0.2f)
                close()
            }
            drawPath(path, PieceFace)
            drawPath(path, PieceEdge, style = Stroke(width = size.width * 0.035f))
            // A soft shadow line along the base for some depth.
            drawLine(
                PieceEdge.copy(alpha = 0.35f),
                Offset(left + w * 0.08f, top + h * 0.9f),
                Offset(left + w * 0.92f, top + h * 0.9f),
                strokeWidth = size.width * 0.02f,
            )
        }
        Text(
            label,
            style = TextStyle(
                fontSize = fontPx,
                fontWeight = if (style == ShogiPieceStyle.KANJI) FontWeight.Bold else FontWeight.ExtraBold,
                color = if (promoted) PromotedInk else Ink,
                textAlign = TextAlign.Center,
            ),
            modifier = Modifier.align(Alignment.Center),
        )
    }
}
