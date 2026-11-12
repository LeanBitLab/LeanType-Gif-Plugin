package com.leanbitlab.leantype.gif.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.leanbitlab.leantype.gif.Contract
import com.leanbitlab.leantype.gif.GifItem
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream

object LocalMediaManager {
    private val SAFE_NAME_REGEX = Regex("[^a-zA-Z0-9_-]")
    private val SUPPORTED_EXTENSIONS = setOf("gif", "webp", "png", "jpg", "jpeg")

    private val localThumbsMap = ConcurrentHashMap<String, File>()
    private val importSeq = AtomicLong(0L)
    private val cacheLock = Any()

    @Volatile
    private var cachedSnapshot: MediaSnapshot? = null
    @Volatile
    private var cacheDirty: Boolean = true

    private data class MediaSnapshot(
        val dirLastModified: Long,
        val items: List<GifItem>,
        val totalBytes: Long
    )

    private data class IndexedFile(
        val file: File,
        val lastModified: Long,
        val length: Long
    )

    private fun nextStamp(): String = "${System.currentTimeMillis()}_${importSeq.incrementAndGet()}"

    fun markDirty() {
        cacheDirty = true
    }

    fun getMediaDir(context: Context): File {
        return File(context.filesDir, "local_media").apply { mkdirs() }
    }

    fun getLocalFileByThumbName(name: String, context: Context): File? {
        localThumbsMap[name]?.let { if (it.exists()) return it }
        val dir = getMediaDir(context)
        val direct = File(dir, name.removePrefix("loc_"))
        if (direct.exists()) {
            localThumbsMap[name] = direct
            return direct
        }
        // Populate all thumb mappings in one cached directory pass instead of linear regex scans per file
        getItems(context)
        localThumbsMap[name]?.let { if (it.exists()) return it }

        val candidate = dir.listFiles()?.firstOrNull { f ->
            "loc_${f.name}" == name
        }
        if (candidate != null) {
            localThumbsMap[name] = candidate
        }
        return candidate
    }

    private fun getOrBuildSnapshot(context: Context): MediaSnapshot {
        val dir = getMediaDir(context)
        val currentModified = dir.lastModified()
        val existing = cachedSnapshot
        if (existing != null && !cacheDirty && existing.dirLastModified == currentModified) {
            return existing
        }

        synchronized(cacheLock) {
            val recheckModified = dir.lastModified()
            val recheck = cachedSnapshot
            if (recheck != null && !cacheDirty && recheck.dirLastModified == recheckModified) {
                return recheck
            }

            val rawFiles = dir.listFiles()
            if (rawFiles.isNullOrEmpty()) {
                localThumbsMap.clear()
                return MediaSnapshot(recheckModified, emptyList(), 0L).also {
                    cachedSnapshot = it
                    cacheDirty = false
                }
            }

            val indexed = ArrayList<IndexedFile>(rawFiles.size)
            var totalBytes = 0L
            for (f in rawFiles) {
                if (!f.isFile) continue
                val fileName = f.name
                if (fileName.startsWith("temp_")) continue
                val len = f.length()
                totalBytes += len
                val ext = f.extension.lowercase()
                if (ext in SUPPORTED_EXTENSIONS) {
                    indexed.add(IndexedFile(f, f.lastModified(), len))
                }
            }

            indexed.sortWith(compareByDescending<IndexedFile> { it.lastModified }.thenBy { it.file.name })

            localThumbsMap.clear()
            val items = ArrayList<GifItem>(indexed.size)
            for (entry in indexed) {
                val file = entry.file
                val cleanExt = file.extension.lowercase()
                val safeBase = file.nameWithoutExtension.replace(SAFE_NAME_REGEX, "_")
                val safeThumbName = "loc_${safeBase}_${file.name.hashCode()}.$cleanExt"
                localThumbsMap[safeThumbName] = file
                val kind = detectKind(file)
                val cleanTitle = file.nameWithoutExtension
                    .removePrefix("meme_")
                    .removePrefix("emoji_")
                    .removePrefix("sticker_")
                    .removePrefix("gif_")
                    .replace('_', ' ')
                    .trim()
                    .ifEmpty { "Media" }

                items.add(
                    GifItem(
                        id = "loc_${file.name.hashCode()}",
                        provider = "local",
                        kind = kind,
                        previewUri = "content://${ThumbRegistry.AUTHORITY}/$safeThumbName",
                        width = 200,
                        height = 200,
                        isPack = false,
                        attribution = cleanTitle,
                        extra = file.absolutePath
                    )
                )
            }

            return MediaSnapshot(dir.lastModified(), items, totalBytes).also {
                cachedSnapshot = it
                cacheDirty = false
            }
        }
    }

    fun getItems(context: Context, registry: ThumbRegistry? = null): List<GifItem> {
        return getOrBuildSnapshot(context).items
    }

    fun detectKind(file: File): Int {
        val name = file.name.lowercase()
        val ext = file.extension.lowercase()
        return when {
            name.startsWith("meme_") || name.contains("meme") -> Contract.KIND_MEME
            name.startsWith("emoji_") || name.contains("emoji") -> Contract.KIND_ANIMATED_EMOJI
            name.startsWith("sticker_") || name.contains("sticker") -> Contract.KIND_STICKER
            name.startsWith("gif_") || ext == "gif" -> Contract.KIND_GIF
            ext == "webp" -> Contract.KIND_STICKER
            else -> Contract.KIND_STICKER
        }
    }

    fun isAnimatedWebp(file: File): Boolean {
        if (!file.exists() || file.length() < 30) return false
        return try {
            val header = ByteArray(64)
            val read = FileInputStream(file).use { it.read(header) }
            if (read >= 21 &&
                header[0] == 'R'.code.toByte() && header[1] == 'I'.code.toByte() &&
                header[2] == 'F'.code.toByte() && header[3] == 'F'.code.toByte() &&
                header[8] == 'W'.code.toByte() && header[9] == 'E'.code.toByte() &&
                header[10] == 'B'.code.toByte() && header[11] == 'P'.code.toByte()
            ) {
                if (header[12] == 'V'.code.toByte() && header[13] == 'P'.code.toByte() &&
                    header[14] == '8'.code.toByte() && header[15] == 'X'.code.toByte()
                ) {
                    val flags = header[20].toInt()
                    return (flags and 0x02) != 0
                }
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    fun convertToWebpSticker(inputFile: File, outputFile: File): Boolean {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(inputFile.absolutePath, options)
            val w = options.outWidth
            val h = options.outHeight
            if (w <= 0 || h <= 0) return false

            val maxDim = maxOf(w, h)
            var sampleSize = 1
            while (maxDim / sampleSize > 1024) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = BitmapFactory.decodeFile(inputFile.absolutePath, decodeOptions) ?: return false

            val currMax = maxOf(bitmap.width, bitmap.height)
            val scale = 512f / currMax
            val targetW = (bitmap.width * scale).toInt().coerceIn(1, 512)
            val targetH = (bitmap.height * scale).toInt().coerceIn(1, 512)

            val scaled = if (targetW != bitmap.width || targetH != bitmap.height) {
                Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
            } else {
                bitmap
            }

            // WhatsApp and Telegram sticker spec requires exact 512x512 canvas with transparent padding
            val canvasBitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(canvasBitmap)
            val left = (512 - targetW) / 2f
            val top = (512 - targetH) / 2f
            canvas.drawBitmap(scaled, left, top, null)

            val baos = ByteArrayOutputStream()
            var quality = 90
            var compressedBytes: ByteArray
            while (true) {
                baos.reset()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val format = if (quality == 90 && canvasBitmap.hasAlpha()) {
                        Bitmap.CompressFormat.WEBP_LOSSLESS
                    } else {
                        Bitmap.CompressFormat.WEBP_LOSSY
                    }
                    canvasBitmap.compress(format, quality, baos)
                } else {
                    @Suppress("DEPRECATION")
                    canvasBitmap.compress(Bitmap.CompressFormat.WEBP, quality, baos)
                }
                compressedBytes = baos.toByteArray()
                // WhatsApp hard limit for static stickers is 100 KB
                if (compressedBytes.size <= 100 * 1024 || quality <= 30) {
                    break
                }
                quality -= 15
            }

            outputFile.writeBytes(compressedBytes)

            if (scaled != bitmap) scaled.recycle()
            bitmap.recycle()
            canvasBitmap.recycle()
            outputFile.exists() && outputFile.length() > 0
        } catch (_: Exception) {
            false
        }
    }

    fun importFiles(context: Context, uris: List<Uri>, targetKind: Int? = null): Int {
        val dir = getMediaDir(context)
        var count = 0
        val contentResolver = context.contentResolver
        try {
            for (uri in uris) {
                runCatching {
                    val mime = contentResolver.getType(uri) ?: ""
                    val displayName = queryDisplayName(context, uri)
                    val ext = when {
                        mime.contains("gif") -> "gif"
                        mime.contains("webp") -> "webp"
                        mime.contains("png") -> "png"
                        mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
                        else -> displayName.substringAfterLast('.', "gif")
                    }.lowercase()
                    val rawName = displayName.substringBeforeLast('.')
                    val safeBase = rawName.replace(SAFE_NAME_REGEX, "_").ifEmpty { "media" }
                    val kindPrefix = when (targetKind) {
                        Contract.KIND_MEME -> "meme"
                        Contract.KIND_ANIMATED_EMOJI -> "emoji"
                        Contract.KIND_STICKER -> "sticker"
                        Contract.KIND_GIF -> "gif"
                        else -> when {
                            rawName.contains("meme", ignoreCase = true) -> "meme"
                            rawName.contains("emoji", ignoreCase = true) -> "emoji"
                            ext == "gif" -> "gif"
                            else -> "sticker"
                        }
                    }

                    val tempFile = File(dir, "temp_${nextStamp()}.$ext")
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    if (!tempFile.exists() || tempFile.length() < 32) {
                        tempFile.delete()
                        return@runCatching
                    }

                    val stamp = nextStamp()
                    // If JPEG or PNG: convert to 512x512 WebP sticker format
                    val targetFile = if (ext in listOf("jpg", "jpeg", "png")) {
                        val webpFile = File(dir, "${kindPrefix}_${safeBase}_${stamp}.webp")
                        if (convertToWebpSticker(tempFile, webpFile)) {
                            tempFile.delete()
                            webpFile
                        } else {
                            // Fallback to original
                            val origFile = File(dir, "${kindPrefix}_${safeBase}_${stamp}.$ext")
                            tempFile.renameTo(origFile)
                            origFile
                        }
                    } else if (ext == "webp") {
                        val webpFile = File(dir, "${kindPrefix}_${safeBase}_${stamp}.webp")
                        if (isAnimatedWebp(tempFile)) {
                            tempFile.renameTo(webpFile)
                            webpFile
                        } else {
                            // Static WebP: scale down if > 512px
                            if (convertToWebpSticker(tempFile, webpFile)) {
                                tempFile.delete()
                                webpFile
                            } else {
                                tempFile.renameTo(webpFile)
                                webpFile
                            }
                        }
                    } else {
                        // GIF or others
                        val finalFile = File(dir, "${kindPrefix}_${safeBase}_${stamp}.$ext")
                        tempFile.renameTo(finalFile)
                        finalFile
                    }

                    if (targetFile.exists() && targetFile.length() > 32) count++ else targetFile.delete()
                }
            }
        } finally {
            if (count > 0) markDirty()
        }
        return count
    }

    fun importZip(context: Context, zipUri: Uri): Int {
        val dir = getMediaDir(context)
        var count = 0
        try {
            runCatching {
                context.contentResolver.openInputStream(zipUri)?.use { input ->
                    ZipInputStream(input).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val name = entry.name
                            val ext = name.substringAfterLast('.', "").lowercase()
                            if (!entry.isDirectory && ext in SUPPORTED_EXTENSIONS) {
                                val rawBase = name.substringAfterLast('/').substringBeforeLast('.')
                                val safeBase = rawBase.replace(SAFE_NAME_REGEX, "_").ifEmpty { "pack" }
                                val entryLower = name.lowercase()
                                val kindPrefix = when {
                                    entryLower.contains("meme") -> "meme"
                                    entryLower.contains("emoji") -> "emoji"
                                    entryLower.contains("sticker") -> "sticker"
                                    entryLower.contains("gif") || ext == "gif" -> "gif"
                                    else -> "sticker"
                                }
                                val tempFile = File(dir, "temp_${nextStamp()}.$ext")
                                FileOutputStream(tempFile).use { fos ->
                                    zis.copyTo(fos)
                                }
                                if (tempFile.exists() && tempFile.length() > 32) {
                                    val stamp = nextStamp()
                                    val targetFile = if (ext in listOf("jpg", "jpeg", "png")) {
                                        val webpFile = File(dir, "${kindPrefix}_${safeBase}_${stamp}.webp")
                                        if (convertToWebpSticker(tempFile, webpFile)) {
                                            tempFile.delete()
                                            webpFile
                                        } else {
                                            val origFile = File(dir, "${kindPrefix}_${safeBase}_${stamp}.$ext")
                                            tempFile.renameTo(origFile)
                                            origFile
                                        }
                                    } else if (ext == "webp") {
                                        val webpFile = File(dir, "${kindPrefix}_${safeBase}_${stamp}.webp")
                                        if (isAnimatedWebp(tempFile)) {
                                            tempFile.renameTo(webpFile)
                                            webpFile
                                        } else {
                                            if (convertToWebpSticker(tempFile, webpFile)) {
                                                tempFile.delete()
                                                webpFile
                                            } else {
                                                tempFile.renameTo(webpFile)
                                                webpFile
                                            }
                                        }
                                    } else {
                                        val finalFile = File(dir, "${kindPrefix}_${safeBase}_${stamp}.$ext")
                                        tempFile.renameTo(finalFile)
                                        finalFile
                                    }
                                    if (targetFile.exists() && targetFile.length() > 32) count++ else targetFile.delete()
                                } else {
                                    tempFile.delete()
                                }
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                }
            }
        } finally {
            if (count > 0) markDirty()
        }
        return count
    }

    fun changeCategory(file: File, newKind: Int): File? {
        if (!file.exists()) return null
        val prefix = when (newKind) {
            Contract.KIND_MEME -> "meme"
            Contract.KIND_ANIMATED_EMOJI -> "emoji"
            Contract.KIND_STICKER -> "sticker"
            else -> "gif"
        }
        val cleanName = file.name
            .removePrefix("meme_")
            .removePrefix("emoji_")
            .removePrefix("sticker_")
            .removePrefix("gif_")
        val newFile = File(file.parentFile, "${prefix}_$cleanName")
        val success = file.renameTo(newFile)
        if (success) {
            localThumbsMap.values.remove(file)
            markDirty()
            return newFile
        }
        return null
    }

    fun clearAll(context: Context): Boolean {
        localThumbsMap.clear()
        return getMediaDir(context).deleteRecursively().also {
            getMediaDir(context).mkdirs()
            markDirty()
        }
    }

    fun deleteFile(file: File): Boolean {
        localThumbsMap.values.remove(file)
        return runCatching { file.delete() }.getOrDefault(false).also { deleted ->
            if (deleted) markDirty()
        }
    }

    fun getTotalBytes(context: Context): Long {
        return getOrBuildSnapshot(context).totalBytes
    }

    fun queryDisplayName(context: Context, uri: Uri): String {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)
                } else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "media.gif"
    }
}
