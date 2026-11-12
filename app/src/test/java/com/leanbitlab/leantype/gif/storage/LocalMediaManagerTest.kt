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
}
