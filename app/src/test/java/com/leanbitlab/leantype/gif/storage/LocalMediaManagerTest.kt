package com.leanbitlab.leantype.gif.storage

import android.content.Context
import android.content.ContextWrapper
import com.leanbitlab.leantype.gif.Contract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalMediaManagerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun testContext(filesRoot: File): Context = object : ContextWrapper(null) {
        override fun getFilesDir(): File = filesRoot
    }

    @Test
    fun testLargeStickerLibraryCachingAndInvalidation() {
        val ctx = testContext(tempFolder.newFolder("files_root"))
        val mediaDir = LocalMediaManager.getMediaDir(ctx)

        val totalFiles = 1250
        val payload = ByteArray(64) { 1 }
        for (i in 0 until totalFiles) {
            val file = File(mediaDir, "sticker_pack_${i}.webp")
            file.writeBytes(payload)
            file.setLastModified(1_700_000_000_000L + i * 10L)
        }
        LocalMediaManager.markDirty()

        val firstRead = LocalMediaManager.getItems(ctx)
        assertEquals(totalFiles, firstRead.size)
        assertEquals(totalFiles * 64L, LocalMediaManager.getTotalBytes(ctx))
        // Newest modified file appears first
        assertTrue(firstRead.first().extra.orEmpty().endsWith("sticker_pack_1249.webp"))

        // Subsequent reads reuse cached snapshot
        val secondRead = LocalMediaManager.getItems(ctx)
        assertSame(firstRead, secondRead)

        // Thumb resolution resolves by generated thumb name
        val sampleThumbName = firstRead[600].previewUri.orEmpty().substringAfterLast('/')
        val resolved = LocalMediaManager.getLocalFileByThumbName(sampleThumbName, ctx)
        assertNotNull(resolved)
        assertEquals(firstRead[600].extra, resolved?.absolutePath)

        // Mutating via deleteFile and changeCategory invalidates cache cleanly
        val firstFile = File(firstRead.first().extra!!)
        assertTrue(LocalMediaManager.deleteFile(firstFile))
        val afterDelete = LocalMediaManager.getItems(ctx)
        assertEquals(totalFiles - 1, afterDelete.size)

        val nextFile = File(afterDelete.first().extra!!)
        val renamed = LocalMediaManager.changeCategory(nextFile, Contract.KIND_MEME)
        assertNotNull(renamed)
        val afterRename = LocalMediaManager.getItems(ctx)
        assertEquals(Contract.KIND_MEME, afterRename.first { it.extra == renamed!!.absolutePath }.kind)

        assertTrue(LocalMediaManager.clearAll(ctx))
        assertEquals(0, LocalMediaManager.getItems(ctx).size)
        assertEquals(0L, LocalMediaManager.getTotalBytes(ctx))
    }

    @Test
    fun testPackNameParsing() {
        assertEquals("Cute Cats", LocalMediaManager.getPackName(File("sticker_Cute_Cats@cat_1_2.webp")))
        assertEquals("Fun", LocalMediaManager.getPackName(File("emoji_Fun@smile_1_2.webp")))
        // files imported before packs existed, or without a pack
        assertEquals(null, LocalMediaManager.getPackName(File("sticker_cat_1_2.webp")))
        assertEquals(null, LocalMediaManager.getPackName(File("gif_some__name_1_2.gif")))
        assertEquals(null, LocalMediaManager.getPackName(File("random.gif")))
    }

    @Test
    fun testPackNameDoesNotChangeKind() {
        // the stored prefix decides the kind, whatever the pack is called
        assertEquals(Contract.KIND_STICKER, LocalMediaManager.detectKind(File("sticker_Memes@a_1_2.webp")))
        assertEquals(Contract.KIND_ANIMATED_EMOJI, LocalMediaManager.detectKind(File("emoji_Sticker_Pack@a_1_2.webp")))
        assertEquals(Contract.KIND_GIF, LocalMediaManager.detectKind(File("gif_Emoji_Fun@a_1_2.gif")))
        // names without a stored prefix keep the old guessing
        assertEquals(Contract.KIND_MEME, LocalMediaManager.detectKind(File("my_meme_1.png")))
        assertEquals(Contract.KIND_GIF, LocalMediaManager.detectKind(File("loop.gif")))
    }

    @Test
    fun testPacksListedPerKindAndOpenedById() {
        val ctx = testContext(tempFolder.newFolder("packs_api_root"))
        val mediaDir = LocalMediaManager.getMediaDir(ctx)
        val payload = ByteArray(64) { 1 }
        fun add(name: String, modified: Long) = File(mediaDir, name).also { it.writeBytes(payload); it.setLastModified(modified) }
        add("sticker_Cute_Cats@cat0_1_0.webp", 1_700_000_000_000L)
        val newestCat = add("sticker_Cute_Cats@cat1_1_1.webp", 1_700_000_001_000L)
        add("sticker_animals@dog_1_2.webp", 1_700_000_002_000L)
        add("meme_Cute_Cats@lol_1_3.gif", 1_700_000_003_000L)
        add("sticker_loose_1_4.webp", 1_700_000_004_000L)
        LocalMediaManager.markDirty()

        val packs = LocalMediaManager.getPacks(ctx)
        // one tile per kind and pack, stickers before memes, names case-insensitive
        assertEquals(
            listOf("${Contract.KIND_STICKER}:animals", "${Contract.KIND_STICKER}:Cute Cats", "${Contract.KIND_MEME}:Cute Cats"),
            packs.map { it.id }
        )
        assertTrue(packs.all { it.isPack })
        val cats = packs[1]
        assertEquals("Cute Cats", cats.attribution)
        assertEquals("2", cats.extra)
        val newestThumb = LocalMediaManager.getItems(ctx).first { it.extra == newestCat.absolutePath }.previewUri
        assertEquals(newestThumb, cats.previewUri)

        assertEquals(2, LocalMediaManager.getPackItems(ctx, cats.id)?.size)
        assertEquals(1, LocalMediaManager.getPackItems(ctx, "${Contract.KIND_MEME}:Cute Cats")?.size)
        // empty pack name opens the items without a pack
        val loose = LocalMediaManager.getPackItems(ctx, LocalMediaManager.packId(Contract.KIND_STICKER, null))
        assertEquals(listOf("loose"), loose?.map { it.attribution })
        assertEquals(null, LocalMediaManager.getPackItems(ctx, "nope"))
        assertEquals(null, LocalMediaManager.getPackItems(ctx, ":Cute Cats"))
    }

    @Test
    fun testPackSurvivesCategoryChangeAndGroupDelete() {
        val ctx = testContext(tempFolder.newFolder("pack_root"))
        val mediaDir = LocalMediaManager.getMediaDir(ctx)
        val payload = ByteArray(64) { 1 }
        val packFiles = (0 until 3).map { File(mediaDir, "sticker_Cute_Cats@cat${it}_1_$it.webp").also { f -> f.writeBytes(payload) } }
        File(mediaDir, "sticker_loose_1_9.webp").writeBytes(payload)
        LocalMediaManager.markDirty()

        val items = LocalMediaManager.getItems(ctx)
        assertEquals(4, items.size)
        // the pack name stays searchable through the title
        assertEquals(3, items.count { it.attribution.orEmpty().contains("Cute Cats") })

        val moved = LocalMediaManager.changeCategory(packFiles[0], Contract.KIND_ANIMATED_EMOJI)
        assertNotNull(moved)
        assertEquals("Cute Cats", LocalMediaManager.getPackName(moved!!))
        assertEquals(Contract.KIND_ANIMATED_EMOJI, LocalMediaManager.detectKind(moved))

        assertEquals(2, LocalMediaManager.deleteFiles(packFiles.drop(1)))
        val left = LocalMediaManager.getItems(ctx)
        assertEquals(2, left.size)
        assertTrue(left.any { it.kind == Contract.KIND_ANIMATED_EMOJI })
    }

    @Test
    fun testTitleHasNoPrefixPackOrStamp() {
        assertEquals("cat", LocalMediaManager.getTitle(File("sticker_Cute_Cats@cat_1700000_5.webp")))
        assertEquals("my cat", LocalMediaManager.getTitle(File("gif_my_cat_1700000_5.gif")))
        // no stamp, no prefix
        assertEquals("plain", LocalMediaManager.getTitle(File("plain.png")))
    }

    @Test
    fun testOrganizeSetPackAndRename() {
        val ctx = testContext(tempFolder.newFolder("organize_root"))
        val mediaDir = LocalMediaManager.getMediaDir(ctx)
        val file = File(mediaDir, "emoji_happy_face_1700000_5.webp").also { it.writeBytes(ByteArray(64) { 1 }) }
        LocalMediaManager.markDirty()

        // into a new pack, kind and stamp stay
        val inPack = LocalMediaManager.setPack(file, "My Faces!")
        assertNotNull(inPack)
        assertEquals("My Faces", LocalMediaManager.getPackName(inPack!!))
        assertEquals("happy face", LocalMediaManager.getTitle(inPack))
        assertEquals(Contract.KIND_ANIMATED_EMOJI, LocalMediaManager.detectKind(inPack))
        assertTrue(inPack.name.endsWith("_1700000_5.webp"))
        assertTrue(!file.exists())

        // rename keeps the pack
        val renamed = LocalMediaManager.renameTitle(inPack, "Very Happy")
        assertNotNull(renamed)
        assertEquals("My Faces", LocalMediaManager.getPackName(renamed!!))
        assertEquals("Very Happy", LocalMediaManager.getTitle(renamed))

        // unusable titles are rejected and leave the file alone
        assertEquals(null, LocalMediaManager.renameTitle(renamed, "!!!"))
        assertTrue(renamed.exists())

        // out of the pack again
        val loose = LocalMediaManager.setPack(renamed, null)
        assertNotNull(loose)
        assertEquals(null, LocalMediaManager.getPackName(loose!!))
        assertEquals("Very Happy", LocalMediaManager.getTitle(loose))

        // refuses to overwrite an existing file
        val other = File(mediaDir, "emoji_Other@Very_Happy_1700000_5.webp").also { it.writeBytes(ByteArray(64) { 1 }) }
        assertEquals(null, LocalMediaManager.setPack(loose, "Other"))
        assertTrue(loose.exists() && other.exists())
    }

    @Test
    fun testOldPathAndThumbNameStillResolveAfterMoves() {
        val ctx = testContext(tempFolder.newFolder("alias_root"))
        val mediaDir = LocalMediaManager.getMediaDir(ctx)
        val original = File(mediaDir, "sticker_happy_1700000_5.webp").also { it.writeBytes(ByteArray(64) { 1 }) }
        LocalMediaManager.markDirty()
        // the thumbnail name a keyboard remembered in its Recent list
        val oldThumb = LocalMediaManager.getItems(ctx).single().previewUri.substringAfterLast('/')
        assertEquals(original, LocalMediaManager.getLocalFileByThumbName(oldThumb, ctx))

        val inPack = LocalMediaManager.setPack(original, "Faces")!!
        val renamed = LocalMediaManager.renameTitle(inPack, "Very Happy")!!
        val recategorized = LocalMediaManager.changeCategory(renamed, Contract.KIND_ANIMATED_EMOJI)!!
        LocalMediaManager.markDirty()

        // the very first path and thumbnail name lead to the final file, however many moves happened
        assertTrue(!original.exists())
        assertEquals(recategorized, LocalMediaManager.resolveMovedFile(ctx, original))
        assertEquals(recategorized, LocalMediaManager.resolveMovedFile(ctx, inPack))
        assertEquals(recategorized, LocalMediaManager.getLocalFileByThumbName(oldThumb, ctx))
        // the current file is returned as is
        assertEquals(recategorized, LocalMediaManager.resolveMovedFile(ctx, recategorized))

        // paths outside the media folder and unknown names are never resolved
        assertEquals(null, LocalMediaManager.resolveMovedFile(ctx, File("/somewhere/else/sticker_happy_1700000_5.webp")))
        assertEquals(null, LocalMediaManager.resolveMovedFile(ctx, File(mediaDir, "never_existed.webp")))
        assertEquals(null, LocalMediaManager.getLocalFileByThumbName("loc_never_existed_1.webp", ctx))
    }

    @Test
    fun testRenamingBackLeavesNoSelfReference() {
        val ctx = testContext(tempFolder.newFolder("back_root"))
        val mediaDir = LocalMediaManager.getMediaDir(ctx)
        val original = File(mediaDir, "gif_loop_1700000_5.gif").also { it.writeBytes(ByteArray(64) { 1 }) }
        val moved = LocalMediaManager.changeCategory(original, Contract.KIND_MEME)!!
        val back = LocalMediaManager.changeCategory(moved, Contract.KIND_GIF)!!
        assertEquals(original, back)
        assertEquals(original, LocalMediaManager.resolveMovedFile(ctx, original))
        // the intermediate name leads to the file that exists now
        assertEquals(original, LocalMediaManager.resolveMovedFile(ctx, moved))
    }

    @Test
    fun testAliasesAreForgottenOnDeleteAndClear() {
        val ctx = testContext(tempFolder.newFolder("forget_root"))
        val mediaDir = LocalMediaManager.getMediaDir(ctx)
        val aliasFile = File(ctx.filesDir, "local_media_aliases.txt")
        val original = File(mediaDir, "sticker_a_1700000_5.webp").also { it.writeBytes(ByteArray(64) { 1 }) }
        val moved = LocalMediaManager.setPack(original, "P")!!
        assertTrue(aliasFile.exists())

        // deleting the file drops the entries that pointed at it
        assertTrue(LocalMediaManager.deleteFile(moved))
        assertEquals(null, LocalMediaManager.resolveMovedFile(ctx, original))
        assertTrue(!aliasFile.exists())

        val second = File(mediaDir, "sticker_b_1700000_6.webp").also { it.writeBytes(ByteArray(64) { 1 }) }
        LocalMediaManager.setPack(second, "P")
        assertTrue(aliasFile.exists())
        assertTrue(LocalMediaManager.clearAll(ctx))
        assertTrue(!aliasFile.exists())
    }

    @Test
    fun testPackRenameAndOrder() {
        val ctx = testContext(tempFolder.newFolder("pack_rename_order_root"))
        val mediaDir = LocalMediaManager.getMediaDir(ctx)
        val payload = ByteArray(64) { 1 }
        fun add(name: String) = File(mediaDir, name).also { it.writeBytes(payload) }
        add("sticker_animals@dog_1_0.webp")
        add("sticker_Cute_Cats@cat0_1_1.webp")
        add("sticker_Cute_Cats@cat1_1_2.webp")
        add("meme_Cute_Cats@lol_1_3.gif")
        add("sticker_zoo@lion_1_4.webp")
        LocalMediaManager.markDirty()
        fun stickerPacks() = LocalMediaManager.getPacks(ctx).filter { it.kind == Contract.KIND_STICKER }.mapNotNull { it.attribution }

        // without a saved order packs are sorted by name
        assertEquals(listOf("animals", "Cute Cats", "zoo"), stickerPacks())
        assertTrue(LocalMediaManager.movePack(ctx, stickerPacks(), "zoo", -2))
        assertEquals(listOf("zoo", "animals", "Cute Cats"), stickerPacks())
        assertTrue(!LocalMediaManager.movePack(ctx, stickerPacks(), "zoo", -1))

        // rename keeps the position and only touches the given kind
        assertEquals(2 to "Funny Cats", LocalMediaManager.renamePack(ctx, Contract.KIND_STICKER, "Cute Cats", "Funny Cats"))
        assertEquals(listOf("zoo", "animals", "Funny Cats"), stickerPacks())
        assertEquals(1, LocalMediaManager.getPackItems(ctx, "${Contract.KIND_MEME}:Cute Cats")?.size)
        // an unusable name never moves files out of their pack
        assertEquals(null, LocalMediaManager.renamePack(ctx, Contract.KIND_STICKER, "zoo", " @! "))
        assertEquals(listOf("zoo", "animals", "Funny Cats"), stickerPacks())
        // renaming to an existing pack merges them
        assertEquals(1 to "animals", LocalMediaManager.renamePack(ctx, Contract.KIND_STICKER, "zoo", "animals"))
        assertEquals(listOf("animals", "Funny Cats"), stickerPacks())
        assertEquals(2, LocalMediaManager.getPackItems(ctx, "${Contract.KIND_STICKER}:animals")?.size)
    }
}
