package io.github.usernamealreadytakensht.games.engine

import android.content.Context
import java.io.File

/**
 * Extracts a whole asset directory (an engine's data files and config) to
 * `filesDir/engines/<name>`, so the engine can be started with that directory as its
 * working directory. Re-extracted after every app update (APK timestamp stamp file).
 */
object EngineAssets {

    fun ensureDir(context: Context, name: String): File {
        val target = File(File(context.filesDir, "engines"), name)
        val stamp = File(target, ".stamp")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        if (target.isDirectory && stamp.exists() && stamp.readText() == version) return target

        target.deleteRecursively()
        copyTree(context, name, target)
        stamp.writeText(version)
        return target
    }

    private fun copyTree(context: Context, assetPath: String, dest: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input -> dest.outputStream().use { input.copyTo(it) } }
            return
        }
        dest.mkdirs()
        for (child in children) copyTree(context, "$assetPath/$child", File(dest, child))
    }
}
