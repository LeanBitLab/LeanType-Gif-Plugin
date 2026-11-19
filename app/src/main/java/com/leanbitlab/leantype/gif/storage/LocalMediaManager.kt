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
    /** Letters and digits of any script (Reação, Ñandú), so pack names and titles keep their accents. */
    private val SAFE_NAME_REGEX = Regex("[^\\p{L}\\p{M}\\p{N}_-]")
    /** Thumbnail names stay ASCII: ThumbnailProvider only serves [a-zA-Z0-9_-] names. */
    private val THUMB_NAME_REGEX = Regex("[^a-zA-Z0-9_-]")
    /** File names are limited to 255 bytes, and accented or non-Latin letters take 2-4 bytes each in UTF-8. */
    private const val MAX_NAME_PART_BYTES = 100
    private val SUPPORTED_EXTENSIONS = setOf("gif", "webp", "png", "jpg", "jpeg")
    private val KIND_PREFIXES = listOf("meme_", "emoji_", "sticker_", "gif_")
    private const val MAX_PACK_LENGTH = 40
    private const val MAX_TITLE_LENGTH = 60
    private const val ALIAS_FILE_NAME = "local_media_aliases.txt"
    private const val PACK_ORDER_FILE_NAME = "local_media_pack_order.txt"
    private const val MAX_ALIAS_HOPS = 8
    private val aliasLock = Any()

    /** "_<millis>_<seq>" appended to every stored name by nextStamp() */
    private val STAMP_REGEX = Regex("_\\d+_\\d+$")

    /** Separates the pack name from the file name in a stored file, SAFE_NAME_REGEX never produces it. */
    private const val PACK_DELIMITER = '@'

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
            return candidate
        }
        // a thumbnail name remembered before the file was renamed
        return resolveMovedThumb(context, name)
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
                val safeThumbName = thumbNameFor(file)
                localThumbsMap[safeThumbName] = file
                val kind = detectKind(file)
                val cleanTitle = file.nameWithoutExtension
                    .removePrefix("meme_")
                    .removePrefix("emoji_")
                    .removePrefix("sticker_")
                    .removePrefix("gif_")
                    .replace(STAMP_REGEX, "")
                    .replace(PACK_DELIMITER, ' ')
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

    private fun packOf(item: GifItem): String? = item.extra?.let { getPackName(File(it)) }

    /** Id the keyboard sends back to open a pack: "<kind>:<pack name>". An empty name means the items without a pack. */
    fun packId(kind: Int, packName: String?): String = "$kind:${packName.orEmpty()}"

    private fun packOrderFile(context: Context) = File(context.filesDir, PACK_ORDER_FILE_NAME)

    /** Pack names in the order the user arranged them, one per line. Packs never moved are not listed. */
    fun getPackOrder(context: Context): List<String> = runCatching {
        packOrderFile(context).takeIf { it.exists() }?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() }
    }.getOrNull().orEmpty()

    private fun writePackOrder(context: Context, order: List<String>) {
        val file = packOrderFile(context)
        val tmp = File(file.parentFile, "$PACK_ORDER_FILE_NAME.tmp")
        runCatching {
            tmp.writeText(order.distinct().joinToString("\n"))
            if (!tmp.renameTo(file)) tmp.delete()
        }
    }

    /** Pack names sorted by the saved [order]; packs missing from it follow, sorted by name. */
    fun sortPacks(names: Collection<String>, order: List<String>): List<String> =
        names.sortedWith(compareBy<String> { order.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it })

    /**
     * Moves [pack] by [offset] places within [shown], the packs in the order they are displayed now, and saves it.
     * Packs of other kinds keep their saved order after the shown ones.
     */
    fun movePack(context: Context, shown: List<String>, pack: String, offset: Int): Boolean {
        val from = shown.indexOf(pack)
        val to = from + offset
        if (from < 0 || to !in shown.indices) return false
        val reordered = shown.toMutableList().apply { add(to, removeAt(from)) }
        writePackOrder(context, reordered + getPackOrder(context).filter { it !in reordered })
        markDirty()
        return true
    }

    /**
     * Renames the pack [oldName] of [kind] by moving its files into [newName]; an existing pack of that name is merged.
     * Returns the number of files moved and the new pack name, or null when the new name is unusable.
     */
    fun renamePack(context: Context, kind: Int, oldName: String, newName: String): Pair<Int, String>? {
        val part = packFileNamePart(newName)
        if (part.isEmpty()) return null
        val displayName = part.removeSuffix(PACK_DELIMITER.toString()).replace('_', ' ').trim()
        val files = getItems(context).filter { it.kind == kind && packOf(it) == oldName }.mapNotNull { it.extra?.let(::File) }
        val moved = files.count { setPack(it, newName) != null }
        if (moved > 0) {
            val order = getPackOrder(context)
            val renamed = order.map { if (it == oldName) displayName else it }
            if (renamed != order) writePackOrder(context, renamed)
        }
        return moved to displayName
    }

    /**
     * One tile per (kind, pack), sorted by kind and then by the user's pack order (see [sortPacks]). The newest item
     * is the cover, attribution is the pack name and extra is the item count.
     */
    fun getPacks(context: Context): List<GifItem> {
        val covers = HashMap<Pair<Int, String>, GifItem>()
        val counts = HashMap<Pair<Int, String>, Int>()
        for (item in getItems(context)) {
            val key = item.kind to (packOf(item) ?: continue)
            // items are newest first, so the first one seen is the cover
            covers.getOrPut(key) { item }
            counts[key] = (counts[key] ?: 0) + 1
        }
        val order = getPackOrder(context)
        val rank = sortPacks(covers.keys.map { it.second }.toSet(), order).withIndex().associate { it.value to it.index }
        return covers.entries
            .sortedWith(compareBy<Map.Entry<Pair<Int, String>, GifItem>> { it.key.first }.thenBy { rank[it.key.second] })
            .map { (key, cover) ->
                cover.copy(
                    id = packId(key.first, key.second),
                    isPack = true,
                    attribution = key.second,
                    extra = counts[key].toString()
                )
            }
    }

    /** Items of the pack named by a [packId], null when the id is malformed. */
    fun getPackItems(context: Context, packId: String): List<GifItem>? {
        val sep = packId.indexOf(':')
        if (sep <= 0) return null
        val kind = packId.substring(0, sep).toIntOrNull() ?: return null
        val pack = packId.substring(sep + 1).ifEmpty { null }
        return getItems(context).filter { it.kind == kind && packOf(it) == pack }
    }

    private fun thumbNameFor(file: File): String {
        val safeBase = file.nameWithoutExtension.replace(THUMB_NAME_REGEX, "_")
        return "loc_${safeBase}_${file.name.hashCode()}.${file.extension.lowercase()}"
    }

    /*
     * Old name -> new name for files that were moved, renamed or recategorized. The keyboard keeps a snapshot of recently
     * used items (file path and thumbnail name), and older keyboard versions cannot be told about the change, so lookups
     * that miss fall back to this map. Stored next to the media folder, one "F|T <old> <new>" line per entry.
     */
    private class Aliases(val files: MutableMap<String, String> = HashMap(), val thumbs: MutableMap<String, String> = HashMap())

    private fun aliasFile(mediaDir: File) = File(mediaDir.parentFile, ALIAS_FILE_NAME)

    private fun readAliases(mediaDir: File): Aliases {
        val aliases = Aliases()
        val file = aliasFile(mediaDir)
        if (!file.exists()) return aliases
        runCatching {
            file.forEachLine { line ->
                val parts = line.split('\t')
                if (parts.size != 3) return@forEachLine
                when (parts[0]) {
                    "F" -> aliases.files[parts[1]] = parts[2]
                    "T" -> aliases.thumbs[parts[1]] = parts[2]
                }
            }
        }
        return aliases
    }

    private fun writeAliases(mediaDir: File, aliases: Aliases) {
        val file = aliasFile(mediaDir)
        if (aliases.files.isEmpty() && aliases.thumbs.isEmpty()) {
            file.delete()
            return
        }
        val text = buildString {
            for ((old, new) in aliases.files) append("F\t$old\t$new\n")
            for ((old, new) in aliases.thumbs) append("T\t$old\t$new\n")
        }
        val tmp = File(file.parentFile, "$ALIAS_FILE_NAME.tmp")
        runCatching {
            tmp.writeText(text)
            if (!tmp.renameTo(file)) tmp.delete()
        }
    }

    private fun recordMove(oldFile: File, newFile: File) {
        val dir = newFile.parentFile ?: return
        synchronized(aliasLock) {
            val aliases = readAliases(dir)
            val oldName = oldFile.name
            val newName = newFile.name
            // entries that pointed at the old name now point at the new one, so a lookup is always a single hop
            for (map in listOf(aliases.files, aliases.thumbs)) {
                for (entry in map.entries) if (entry.value == oldName) entry.setValue(newName)
            }
            aliases.files[oldName] = newName
            aliases.thumbs[thumbNameFor(oldFile)] = newName
            // renaming back to an earlier name must not leave an entry that points at itself
            aliases.files.remove(newName)
            aliases.thumbs.remove(thumbNameFor(newFile))
            writeAliases(dir, aliases)
        }
    }

    private fun forgetAliasesTo(file: File) {
        val dir = file.parentFile ?: return
        synchronized(aliasLock) {
            if (!aliasFile(dir).exists()) return
            val aliases = readAliases(dir)
            val before = aliases.files.size + aliases.thumbs.size
            aliases.files.values.removeAll { it == file.name }
            aliases.thumbs.values.removeAll { it == file.name }
            if (aliases.files.size + aliases.thumbs.size != before) writeAliases(dir, aliases)
        }
    }

    private fun followAlias(dir: File, aliases: Aliases, startName: String): File? {
        var name = startName
        repeat(MAX_ALIAS_HOPS) {
            val next = aliases.files[name] ?: return null
            val candidate = File(dir, next)
            if (candidate.exists()) return candidate
            name = next
        }
        return null
    }

    /** The current file for a path that was valid before the file was moved or renamed, null if there is none. */
    fun resolveMovedFile(context: Context, file: File): File? {
        if (file.exists()) return file
        val dir = getMediaDir(context)
        if (file.parentFile != dir) return null
        synchronized(aliasLock) { return followAlias(dir, readAliases(dir), file.name) }
    }

    private fun resolveMovedThumb(context: Context, thumbName: String): File? {
        val dir = getMediaDir(context)
        synchronized(aliasLock) {
            val aliases = readAliases(dir)
            val target = aliases.thumbs[thumbName] ?: return null
            val candidate = File(dir, target)
            return if (candidate.exists()) candidate else followAlias(dir, aliases, target)
        }
    }

    private class StoredName(val packRaw: String?, val title: String, val stamp: String?)

    private fun parseStoredName(file: File): StoredName {
        val base = file.nameWithoutExtension
        var rest = KIND_PREFIXES.firstOrNull { base.startsWith(it) }?.let { base.removePrefix(it) } ?: base
        val end = rest.indexOf(PACK_DELIMITER)
        var packRaw: String? = null
        if (end >= 0) {
            packRaw = rest.substring(0, end).ifEmpty { null }
            rest = rest.substring(end + 1)
        }
        val stamp = STAMP_REGEX.find(rest)?.value
        return StoredName(packRaw, if (stamp != null) rest.removeSuffix(stamp) else rest, stamp)
    }

    private fun kindPrefix(kind: Int): String = when (kind) {
        Contract.KIND_MEME -> "meme"
        Contract.KIND_ANIMATED_EMOJI -> "emoji"
        Contract.KIND_STICKER -> "sticker"
        else -> "gif"
    }

    /** Title shown for a stored file: no kind prefix, pack or stamp, underscores as spaces. */
    fun getTitle(file: File): String = parseStoredName(file).title.replace('_', ' ').trim()

    private fun renameStored(file: File, packPart: String, title: String): File? {
        if (!file.exists()) return null
        val parsed = parseStoredName(file)
        val ext = file.extension.lowercase()
        val stamp = parsed.stamp ?: "_${nextStamp()}"
        val newFile = File(file.parentFile, "${kindPrefix(detectKind(file))}_${packPart}${title}${stamp}.$ext")
        if (newFile == file) return file
        if (newFile.exists() || !file.renameTo(newFile)) return null
        localThumbsMap.values.remove(file)
        recordMove(file, newFile)
        markDirty()
        return newFile
    }

    /** Moves a file into the given pack, or out of any pack when [packName] is blank. */
    fun setPack(file: File, packName: String?): File? =
        renameStored(file, packFileNamePart(packName), parseStoredName(file).title)

    /** Changes the title, keeping kind, pack and the unique stamp. Null when the new title is unusable. */
    fun renameTitle(file: File, newTitle: String): File? {
        val safe = safeNamePart(newTitle, MAX_TITLE_LENGTH)
        if (safe.isEmpty()) return null
        val packRaw = parseStoredName(file).packRaw
        return renameStored(file, if (packRaw == null) "" else "$packRaw$PACK_DELIMITER", safe)
    }

    /** Pack name stored in the file name, or null. Shown with spaces instead of underscores. */
    fun getPackName(file: File): String? {
        val base = file.nameWithoutExtension
        val rest = KIND_PREFIXES.firstOrNull { base.startsWith(it) }?.let { base.removePrefix(it) } ?: base
        val end = rest.indexOf(PACK_DELIMITER)
        if (end <= 0) return null
        return rest.substring(0, end).replace('_', ' ').trim().ifEmpty { null }
    }

    /** [raw] as a file name part: NFC, unsafe characters as single "_", at most [maxChars] and [MAX_NAME_PART_BYTES]. */
    private fun safeNamePart(raw: String, maxChars: Int): String =
        capUtf8(
            java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFC)
                .replace(SAFE_NAME_REGEX, "_")
                .replace(Regex("_+"), "_")
                .trim('_', '-')
                .take(maxChars)
        ).trim('_', '-')

    /** Cuts [s] at a code point boundary so it takes at most [MAX_NAME_PART_BYTES] bytes in UTF-8. */
    private fun capUtf8(s: String): String {
        var bytes = 0
        var end = 0
        while (end < s.length) {
            val cp = s.codePointAt(end)
            val len = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + len > MAX_NAME_PART_BYTES) break
            bytes += len
            end += Character.charCount(cp)
        }
        return s.substring(0, end)
    }

    /** File name part for an imported pack, "" when there is no usable pack name. */
    private fun packFileNamePart(packName: String?): String {
        val safe = safeNamePart(packName.orEmpty(), MAX_PACK_LENGTH)
        return if (safe.isEmpty()) "" else "$safe$PACK_DELIMITER"
    }

    fun detectKind(file: File): Int {
        val name = file.name.lowercase()
        val ext = file.extension.lowercase()
        // the stored prefix wins, so a pack called "Memes" cannot change the kind of its stickers
        KIND_PREFIXES.firstOrNull { name.startsWith(it) }?.let {
            return when (it) {
                "meme_" -> Contract.KIND_MEME
                "emoji_" -> Contract.KIND_ANIMATED_EMOJI
                "sticker_" -> Contract.KIND_STICKER
                else -> Contract.KIND_GIF
            }
        }
        return when {
            name.contains("meme") -> Contract.KIND_MEME
            name.contains("emoji") -> Contract.KIND_ANIMATED_EMOJI
            name.contains("sticker") -> Contract.KIND_STICKER
            ext == "gif" -> Contract.KIND_GIF
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

    fun importFiles(context: Context, uris: List<Uri>, targetKind: Int? = null, packName: String? = null): Int {
        val dir = getMediaDir(context)
        var count = 0
        val packPart = packFileNamePart(packName)
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
                    val safeBase = packPart + capUtf8(rawName.replace(SAFE_NAME_REGEX, "_")).ifEmpty { "media" }
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
        val packPart = packFileNamePart(queryDisplayName(context, zipUri).substringBeforeLast('.'))
        try {
            runCatching {
                context.contentResolver.openInputStream(zipUri)?.use { input ->
                    ZipInputStream(input).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val name = entry.name
                            val ext = name.substringAfterLast('.', "").lowercase()
                            // skip macOS resource fork junk, it is not media
                            if (!entry.isDirectory && ext in SUPPORTED_EXTENSIONS && !name.startsWith("__MACOSX/")) {
                                val rawBase = name.substringAfterLast('/').substringBeforeLast('.')
                                val safeBase = packPart + capUtf8(rawBase.replace(SAFE_NAME_REGEX, "_")).ifEmpty { "pack" }
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
            recordMove(file, newFile)
            markDirty()
            return newFile
        }
        return null
    }

    fun clearAll(context: Context): Boolean {
        localThumbsMap.clear()
        synchronized(aliasLock) { aliasFile(getMediaDir(context)).delete() }
        return getMediaDir(context).deleteRecursively().also {
            getMediaDir(context).mkdirs()
            markDirty()
        }
    }

    fun deleteFile(file: File): Boolean {
        localThumbsMap.values.remove(file)
        return runCatching { file.delete() }.getOrDefault(false).also { deleted ->
            if (deleted) {
                forgetAliasesTo(file)
                markDirty()
            }
        }
    }

    fun deleteFiles(files: List<File>): Int = files.count { deleteFile(it) }

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
