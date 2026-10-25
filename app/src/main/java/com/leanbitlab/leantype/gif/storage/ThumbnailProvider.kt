package com.leanbitlab.leantype.gif.storage

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.leanbitlab.leantype.gif.security.CallerCheck
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.util.regex.Pattern

class ThumbnailProvider : ContentProvider() {
    companion object {
        private val FILENAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+\\.(webp|jpg|jpeg|png|gif)$")
    }

    private lateinit var registry: ThumbRegistry

    override fun onCreate(): Boolean {
        registry = ThumbRegistry(context!!)
        return true
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException("No context")
        CallerCheck.enforceHost(ctx)

        if (mode != "r") {
            throw SecurityException("Write mode '$mode' is prohibited")
        }

        val lastSegment = uri.lastPathSegment ?: throw FileNotFoundException("Empty path")
        if (!FILENAME_PATTERN.matcher(lastSegment).matches()) {
            throw SecurityException("Malformed thumbnail file name '$lastSegment'")
        }

        // Direct local media resolution: bypasses LRU cache and SQLite row limit for imported media
        if (lastSegment.startsWith("loc_")) {
            val localFile = LocalMediaManager.getLocalFileByThumbName(lastSegment, ctx)
            if (localFile != null && localFile.exists() && localFile.length() >= 32) {
                if (!validateMagic(localFile)) {
                    throw FileNotFoundException("Invalid image magic for: $lastSegment")
                }
                return ParcelFileDescriptor.open(localFile, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }

        val file = registry.getFile(lastSegment)
        if (!file.exists() || file.length() < 32) {
            val registeredUrl = registry.getUrl(lastSegment)
            if (registeredUrl != null && registeredUrl.startsWith("file://")) {
                val srcFile = File(registeredUrl.removePrefix("file://"))
                if (srcFile.exists() && srcFile.length() >= 32) {
                    runCatching { srcFile.copyTo(file, overwrite = true) }
                }
            }
        }
        if (!file.exists() || file.length() < 32) {
            throw FileNotFoundException("File not found: $lastSegment")
        }

        if (!validateMagic(file)) {
            file.delete()
            throw FileNotFoundException("Invalid image magic for: $lastSegment")
        }

        registry.updateAccess(lastSegment)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun validateMagic(file: File): Boolean {
        return try {
            val header = ByteArray(12)
            FileInputStream(file).use { it.read(header) }
            when {
                // GIF87a or GIF89a
                header[0] == 'G'.code.toByte() && header[1] == 'I'.code.toByte() && header[2] == 'F'.code.toByte() -> true
                // PNG
                header[0] == 0x89.toByte() && header[1] == 'P'.code.toByte() && header[2] == 'N'.code.toByte() && header[3] == 'G'.code.toByte() -> true
                // JPEG
                header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte() -> true
                // WebP (RIFF....WEBP)
                header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() && header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte() &&
                    header[8] == 'W'.code.toByte() && header[9] == 'E'.code.toByte() && header[10] == 'B'.code.toByte() && header[11] == 'P'.code.toByte() -> true
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun getType(uri: Uri): String? {
        val segment = uri.lastPathSegment ?: return null
        return when {
            segment.endsWith(".webp", ignoreCase = true) -> "image/webp"
            segment.endsWith(".gif", ignoreCase = true) -> "image/gif"
            segment.endsWith(".png", ignoreCase = true) -> "image/png"
            segment.endsWith(".jpg", ignoreCase = true) || segment.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
            else -> null
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
