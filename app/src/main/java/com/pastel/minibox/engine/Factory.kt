package com.pastel.minibox.engine

import android.content.Context
import java.io.File

/** Factory content (presets, samples, MIDI maps) ships in the APK's assets and is unpacked into app storage the
    first time a version runs, so the C++ core can read it as plain files. */
object Factory {
    fun unpack(context: Context): File {
        val dir = File(context.filesDir, "Factory")
        val stamp = File(dir, ".version")
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
        if (stamp.isFile && stamp.readText() == version) return dir
        dir.deleteRecursively()
        copy(context, "Factory", dir)
        stamp.writeText(version)
        return dir
    }

    private fun copy(context: Context, path: String, to: File) {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            to.parentFile?.mkdirs()
            context.assets.open(path).use { input -> to.outputStream().use { input.copyTo(it, 1 shl 16) } }
            return
        }
        to.mkdirs()
        for (c in children) copy(context, "$path/$c", File(to, c))
    }
}
