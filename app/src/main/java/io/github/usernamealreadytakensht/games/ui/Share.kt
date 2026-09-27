package io.github.usernamealreadytakensht.games.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.core.content.FileProvider
import io.github.usernamealreadytakensht.games.R
import io.github.usernamealreadytakensht.games.game.record.ExportedGame
import java.io.File

/**
 * Opens Android's share sheet with the game both as text (to paste into a chat or an
 * analysis site) and as an attached file (.pgn, .pdn, .kif or .txt) served by FileProvider.
 */
fun shareGame(context: Context, game: ExportedGame) {
    val dir = File(context.cacheDir, "exports").apply { mkdirs() }
    val file = File(dir, game.fileName).apply { writeText(game.text) }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = game.mimeType
        putExtra(Intent.EXTRA_SUBJECT, game.fileName)
        putExtra(Intent.EXTRA_TEXT, game.text)
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(game.fileName, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share game"))
}

/** Share icon for a game screen's top bar. */
@Composable
fun ShareGameButton(export: () -> ExportedGame) {
    val context = LocalContext.current
    IconButton(onClick = { shareGame(context, export()) }) {
        Icon(painterResource(R.drawable.ic_share), contentDescription = "Share game")
    }
}
