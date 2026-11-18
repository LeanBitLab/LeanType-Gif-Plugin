package com.leanbitlab.leantype.gif.providers

import android.content.Context
import com.leanbitlab.leantype.gif.Contract
import com.leanbitlab.leantype.gif.GifItem
import com.leanbitlab.leantype.gif.ProviderInfo
import com.leanbitlab.leantype.gif.storage.LocalMediaManager
import com.leanbitlab.leantype.gif.storage.ThumbRegistry
import java.io.File

class LocalMediaProvider(
    private val context: Context,
    private val registry: ThumbRegistry
) : GifProvider {
    companion object {
        const val ID = "local"
        private const val PAGE_SIZE = 24
    }

    override val info: ProviderInfo
        get() = ProviderInfo(
            id = ID,
            displayName = "Local",
            kinds = Contract.KINDS_GIF or Contract.KINDS_STICKER or Contract.KINDS_MEME or Contract.KINDS_ANIMATED_EMOJI,
            capabilities = Contract.CAP_TRENDING or Contract.CAP_SEARCH or Contract.CAP_PACKS,
            credentialFields = emptyList(),
            configured = true,
            needsNetwork = false,
            attribution = "Local Storage"
        )

    override suspend fun search(query: String, offset: String?, kind: Int): ProviderResult {
        val allItems = LocalMediaManager.getItems(context, registry)
        val q = query.trim().lowercase()
        val tokens = if (q.isNotEmpty()) q.split("\\s+".toRegex()) else emptyList()

        val filtered = allItems.filter { item ->
            val matchesKind = (item.kind == kind)
            val name = (item.attribution ?: "").lowercase()
            val matchesQuery = tokens.isEmpty() || tokens.all { name.contains(it) }
            matchesKind && matchesQuery
        }

        return page(filtered, offset)
    }

    private fun page(items: List<GifItem>, offset: String?): ProviderResult {
        val offsetInt = offset?.toIntOrNull() ?: 0
        val page = items.drop(offsetInt).take(PAGE_SIZE)
        val nextOffset = if (offsetInt + page.size < items.size) (offsetInt + page.size).toString() else null
        return ProviderResult.Success(page, nextOffset)
    }

    override suspend fun trending(offset: String?, kind: Int): ProviderResult {
        return search("", offset, kind)
    }

    // all packs in one page: there are few and the keyboard shows them before the items
    override suspend fun listPacks(offset: String?): ProviderResult =
        ProviderResult.Success(LocalMediaManager.getPacks(context), null)

    override suspend fun getPack(packId: String, offset: String?): ProviderResult {
        val items = LocalMediaManager.getPackItems(context, packId)
            ?: return ProviderResult.Error(Contract.ERR_BAD_REQUEST, "Bad pack id")
        return page(items, offset)
    }

    override suspend fun resolveFullMediaUrl(item: GifItem): String? {
        val path = item.extra ?: return null
        // an item from the keyboard's Recent list may point at a file that was renamed or moved since
        return LocalMediaManager.resolveMovedFile(context, File(path))?.absolutePath ?: path
    }
}
