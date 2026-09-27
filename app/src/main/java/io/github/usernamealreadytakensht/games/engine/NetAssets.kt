package io.github.usernamealreadytakensht.games.engine

import android.content.Context
import java.io.File

/** Copies neural-network files from `assets/nets` to the files directory, where engines can read them. */
object NetAssets {

    private const val ASSET_DIR = "nets"

    /**
     * Returns the on-disk copy of [name], extracting it from the APK if missing or stale.
     * The copy is written to a temporary file and renamed, so a present file is always
     * complete; staleness is judged on the uncompressed size, which also works for assets
     * stored compressed in the APK (the .nnue nets).
     */
    @Synchronized
    fun ensure(context: Context, name: String): File {
        val dir = File(context.filesDir, ASSET_DIR).apply { mkdirs() }
        val target = File(dir, name)
        val assetSize = assetLength(context, "$ASSET_DIR/$name")
        if (target.exists() && (assetSize == null || target.length() == assetSize)) return target

        val tmp = File(dir, "$name.tmp")
        context.assets.open("$ASSET_DIR/$name").use { input ->
            tmp.outputStream().use { output -> input.copyTo(output) }
        }
        target.delete()
        check(tmp.renameTo(target)) { "Cannot install $name" }
        return target
    }

    /** Uncompressed size of an asset: the file descriptor length when stored, else the stream's. */
    private fun assetLength(context: Context, path: String): Long? =
        runCatching { context.assets.openFd(path).use { it.length } }.getOrNull()
            ?: runCatching { context.assets.open(path).use { it.available().toLong() } }.getOrNull()

    /**
     * Deletes extracted networks the APK no longer ships (renamed or dropped by an update),
     * and temporary files left by an interrupted copy. Cheap; run once at startup.
     * Synchronized with [ensure], so it never deletes a copy in progress.
     */
    @Synchronized
    fun pruneStale(context: Context) {
        val dir = File(context.filesDir, ASSET_DIR)
        val shipped = context.assets.list(ASSET_DIR).orEmpty().toSet()
        dir.listFiles()?.forEach { f -> if (f.isFile && f.name !in shipped) f.delete() }
    }

    /**
     * Extracts a whole asset folder (e.g. an engine's data directory) to the files directory
     * and returns it. Re-extracted whenever the app's version code changes.
     */
    fun ensureDir(context: Context, assetDir: String): File {
        val dir = File(context.filesDir, assetDir)
        val stamp = File(dir, ".version")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toString()
        if (dir.isDirectory && stamp.exists() && stamp.readText() == version) return dir
        dir.deleteRecursively()
        copyAssetTree(context, assetDir, dir)
        stamp.writeText(version)
        return dir
    }

    private fun copyAssetTree(context: Context, assetPath: String, target: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input -> target.outputStream().use { input.copyTo(it) } }
            return
        }
        target.mkdirs()
        for (child in children) copyAssetTree(context, "$assetPath/$child", File(target, child))
    }
}
