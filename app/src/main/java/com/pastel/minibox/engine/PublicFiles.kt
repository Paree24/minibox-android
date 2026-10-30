package com.pastel.minibox.engine

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Projects saved where the phone's Files app shows them: Documents/Minibox in the main storage. Android 10+ writes
    there through MediaStore (no permission); Android 9 writes the folder directly (storage permission). */
object PublicFiles {
    const val FOLDER = "Documents/Minibox"

    /** Copies `src` to Documents/Minibox/<fileName>, replacing a file of that name the app saved before. */
    fun save(context: Context, src: File, fileName: String): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            val resolver = context.contentResolver
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val existing = resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf("$FOLDER/", fileName), null)?.use { c ->
                if (c.moveToFirst()) Uri.withAppendedPath(collection, c.getLong(0).toString()) else null
            }
            val uri = existing ?: resolver.insert(collection, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER)
            }) ?: return false
            resolver.openOutputStream(uri, "wt")?.use { out -> src.inputStream().use { it.copyTo(out) } } ?: return false
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "Minibox").apply { mkdirs() }
            src.copyTo(File(dir, fileName), overwrite = true)
        }
        true
    }.getOrDefault(false)

    /** Copies `src` into a document the user picked (Save as…). */
    fun write(context: Context, src: File, uri: Uri): Boolean = runCatching {
        context.contentResolver.openOutputStream(uri, "wt")?.use { out -> src.inputStream().use { it.copyTo(out) } } != null
    }.getOrDefault(false)
}
