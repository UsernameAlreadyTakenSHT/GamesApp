package io.github.usernamealreadytakensht.games.engine

import android.content.Context
import java.io.File

/** Copies neural-network files from `assets/nets` to the files directory, where engines can read them. */
object NetAssets {

    private const val ASSET_DIR = "nets"

    /** Returns the on-disk copy of [name], extracting it from the APK if missing or stale. */
    fun ensure(context: Context, name: String): File {
        val dir = File(context.filesDir, ASSET_DIR).apply { mkdirs() }
        val target = File(dir, name)
        // Works because .lc0 assets are stored uncompressed (see noCompress in build.gradle.kts).
        val assetSize = runCatching { context.assets.openFd("$ASSET_DIR/$name").use { it.length } }.getOrNull()
        if (target.exists() && (assetSize == null || target.length() == assetSize)) return target

        val tmp = File(dir, "$name.tmp")
        context.assets.open("$ASSET_DIR/$name").use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
        return target
    }
}
