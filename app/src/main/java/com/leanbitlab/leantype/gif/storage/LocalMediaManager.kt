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
import java.util.zip.ZipInputStream

object LocalMediaManager {
    private val localThumbsMap = ConcurrentHashMap<String, File>()

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
        val candidate = dir.listFiles()?.firstOrNull { f ->
            val cleanExt = f.extension.lowercase()
            val safeBase = f.nameWithoutExtension.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
            val expected = "loc_${safeBase}_${f.name.hashCode()}.$cleanExt"
            expected == name || "loc_${f.name}" == name
        }
        if (candidate != null) {
            localThumbsMap[name] = candidate
        }
        return candidate
    }

    fun getItems(context: Context, registry: ThumbRegistry? = null): List<GifItem> {
        val dir = getMediaDir(context)
        val files = dir.listFiles { f ->
            val ext = f.extension.lowercase()
            ext in listOf("gif", "webp", "png", "jpg", "jpeg")
        }?.sortedByDescending { it.lastModified() } ?: return emptyList()

        return files.map { file ->
            val cleanExt = file.extension.lowercase()
            val safeBase = file.nameWithoutExtension.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
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
        }
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
        for (uri in uris) {
            runCatching {
                val mime = contentResolver.getType(uri) ?: ""
                val ext = when {
                    mime.contains("gif") -> "gif"
                    mime.contains("webp") -> "webp"
                    mime.contains("png") -> "png"
                    mime.contains("jpeg") || mime.contains("jpg") -> "jpg"
                    else -> queryDisplayName(context, uri).substringAfterLast('.', "gif")
                }.lowercase()
                val rawName = queryDisplayName(context, uri).substringBeforeLast('.')
                val safeBase = rawName.replace("[^a-zA-Z0-9_-]".toRegex(), "_").ifEmpty { "media" }
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

                val tempFile = File(dir, "temp_${System.currentTimeMillis()}.$ext")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                if (!tempFile.exists() || tempFile.length() < 32) {
                    tempFile.delete()
                    return@runCatching
                }

                // If JPEG or PNG: convert to 512x512 WebP sticker format
                val targetFile = if (ext in listOf("jpg", "jpeg", "png")) {
                    val webpFile = File(dir, "${kindPrefix}_${safeBase}_${System.currentTimeMillis()}.webp")
                    if (convertToWebpSticker(tempFile, webpFile)) {
                        tempFile.delete()
                        webpFile
                    } else {
                        // Fallback to original
                        val origFile = File(dir, "${kindPrefix}_${safeBase}_${System.currentTimeMillis()}.$ext")
                        tempFile.renameTo(origFile)
                        origFile
                    }
                } else if (ext == "webp") {
                    val webpFile = File(dir, "${kindPrefix}_${safeBase}_${System.currentTimeMillis()}.webp")
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
                    val finalFile = File(dir, "${kindPrefix}_${safeBase}_${System.currentTimeMillis()}.$ext")
                    tempFile.renameTo(finalFile)
                    finalFile
                }

                if (targetFile.exists() && targetFile.length() > 32) count++ else targetFile.delete()
            }
        }
        return count
    }

    fun importZip(context: Context, zipUri: Uri): Int {
        val dir = getMediaDir(context)
        var count = 0
        runCatching {
            context.contentResolver.openInputStream(zipUri)?.use { input ->
                ZipInputStream(input).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val name = entry.name
                        val ext = name.substringAfterLast('.', "").lowercase()
                        if (!entry.isDirectory && ext in listOf("gif", "webp", "png", "jpg", "jpeg")) {
                            val rawBase = name.substringAfterLast('/').substringBeforeLast('.')
                            val safeBase = rawBase.replace("[^a-zA-Z0-9_-]".toRegex(), "_").ifEmpty { "pack" }
                            val entryLower = name.lowercase()
                            val kindPrefix = when {
                                entryLower.contains("meme") -> "meme"
                                entryLower.contains("emoji") -> "emoji"
                                entryLower.contains("sticker") -> "sticker"
                                entryLower.contains("gif") || ext == "gif" -> "gif"
                                else -> "sticker"
                            }
                            val tempFile = File(dir, "temp_${System.currentTimeMillis()}.$ext")
                            FileOutputStream(tempFile).use { fos ->
                                zis.copyTo(fos)
                            }
                            if (tempFile.exists() && tempFile.length() > 32) {
                                val targetFile = if (ext in listOf("jpg", "jpeg", "png")) {
                                    val webpFile = File(dir, "${kindPrefix}_${safeBase}_${System.currentTimeMillis()}.webp")
                                    if (convertToWebpSticker(tempFile, webpFile)) {
                                        tempFile.delete()
                                        webpFile
                                    } else {
                                        val origFile = File(dir, "${kindPrefix}_${safeBase}_${System.currentTimeMillis()}.$ext")
                                        tempFile.renameTo(origFile)
                                        origFile
                                    }
                                } else if (ext == "webp") {
                                    val webpFile = File(dir, "${kindPrefix}_${safeBase}_${System.currentTimeMillis()}.webp")
                                    if (isAnimatedWebp(tempFile)) {
                                        tempFile.renameTo(webpFile)
                                        webpFile
                                    } else {
                                        val webpFileConverted = File(dir, "${kindPrefix}_${safeBase}_${System.currentTimeMillis()}.webp")
                                        if (convertToWebpSticker(tempFile, webpFileConverted)) {
                                            tempFile.delete()
                                            webpFileConverted
                                        } else {
                                            tempFile.renameTo(webpFile)
                                            webpFile
                                        }
                                    }
                                } else {
                                    val finalFile = File(dir, "${kindPrefix}_${safeBase}_${System.currentTimeMillis()}.$ext")
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
            return newFile
        }
        return null
    }

    fun clearAll(context: Context): Boolean {
        localThumbsMap.clear()
        return getMediaDir(context).deleteRecursively().also {
            getMediaDir(context).mkdirs()
        }
    }

    fun deleteFile(file: File): Boolean {
        localThumbsMap.values.remove(file)
        return runCatching { file.delete() }.getOrDefault(false)
    }

    fun getTotalBytes(context: Context): Long {
        val files = getMediaDir(context).listFiles() ?: return 0L
        return files.sumOf { if (it.isFile) it.length() else 0L }
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
