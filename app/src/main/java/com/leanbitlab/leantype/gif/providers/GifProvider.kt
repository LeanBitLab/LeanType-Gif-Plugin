package com.leanbitlab.leantype.gif.providers

import com.leanbitlab.leantype.gif.Contract
import com.leanbitlab.leantype.gif.GifItem
import com.leanbitlab.leantype.gif.ProviderInfo

sealed class ProviderResult {
    data class Success(val items: List<GifItem>, val nextOffset: String?) : ProviderResult()
    data class Error(val errorCode: Int, val message: String) : ProviderResult()
}

interface GifProvider {
    val info: ProviderInfo
    suspend fun search(query: String, offset: String?, kind: Int = Contract.KIND_GIF): ProviderResult
    suspend fun trending(offset: String?, kind: Int = Contract.KIND_GIF): ProviderResult
    suspend fun resolveFullMediaUrl(item: GifItem): String?

    /** Only for providers that report Contract.CAP_PACKS. */
    suspend fun listPacks(offset: String?): ProviderResult =
        ProviderResult.Error(Contract.ERR_BAD_REQUEST, "Packs not supported")

    suspend fun getPack(packId: String, offset: String?): ProviderResult =
        ProviderResult.Error(Contract.ERR_BAD_REQUEST, "Packs not supported")
}
