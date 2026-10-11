package io.github.usernamealreadytakensht.games.ui

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Tint of the squares (or ring of the points) of a suggested move. */
internal val HintTint = Color(0x8048B04C)

/** "Hint" button of the game screens; spins while the engine looks for the move. */
@Composable
internal fun HintButton(loading: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, enabled = enabled && !loading, modifier = modifier) {
        if (loading) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        else Text("Hint")
    }
}
