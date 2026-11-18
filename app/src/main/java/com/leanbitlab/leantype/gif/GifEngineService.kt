package com.leanbitlab.leantype.gif

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import com.leanbitlab.leantype.gif.providers.GifProvider
import com.leanbitlab.leantype.gif.providers.GiphyProvider
import com.leanbitlab.leantype.gif.providers.KlipyProvider
import com.leanbitlab.leantype.gif.providers.ProviderResult
import com.leanbitlab.leantype.gif.security.CallerCheck
import com.leanbitlab.leantype.gif.storage.CredentialStore
import com.leanbitlab.leantype.gif.storage.FetchCache
import com.leanbitlab.leantype.gif.storage.ThumbRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

class GifEngineService : Service() {
    companion object {
        private const val TAG = "GifEngineService"
        private const val MAX_IN_FLIGHT = 16
        private const val REQUEST_TIMEOUT_MS = 12_000L
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var credentials: CredentialStore
    private lateinit var thumbRegistry: ThumbRegistry
    private lateinit var fetchCache: FetchCache
    private val providers = mutableMapOf<String, GifProvider>()
    private val inFlight = ConcurrentHashMap<Int, Job>()

    override fun onCreate() {
        super.onCreate()
        credentials = CredentialStore(applicationContext)
        thumbRegistry = ThumbRegistry(applicationContext)
        fetchCache = FetchCache(applicationContext)

        val giphy = GiphyProvider(credentials, thumbRegistry)
        val klipy = KlipyProvider(credentials, thumbRegistry)
        val local = com.leanbitlab.leantype.gif.providers.LocalMediaProvider(applicationContext, thumbRegistry)
        providers[giphy.info.id] = giphy
        providers[klipy.info.id] = klipy
        providers[local.info.id] = local
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private val binder = object : IGifEngine.Stub() {
        override fun getContractVersion(): Int {
            CallerCheck.enforceHost(this@GifEngineService)
            return Contract.VERSION
        }

        override fun listProviders(): List<ProviderInfo> {
            CallerCheck.enforceHost(this@GifEngineService)
            runBlocking { credentials.awaitWarm(2000L) }
            return providers.values.map { it.info }
        }

        override fun search(requestId: Int, providerId: String?, query: String?, offset: String?, limit: Int, callback: IGifCallback?) {
            searchKind(requestId, providerId, Contract.KIND_GIF, query, offset, limit, callback)
        }

        override fun trending(requestId: Int, providerId: String?, offset: String?, limit: Int, callback: IGifCallback?) {
            trendingKind(requestId, providerId, Contract.KIND_GIF, offset, limit, callback)
        }

        override fun searchKind(requestId: Int, providerId: String?, kind: Int, query: String?, offset: String?, limit: Int, callback: IGifCallback?) {
            CallerCheck.enforceHost(this@GifEngineService)
            if (callback == null) return
            launchRequest(requestId, callback) {
                val requested = providers[providerId]
                val provider = if (requested?.info?.configured == true) {
                    requested
                } else {
                    providers.values.firstOrNull { it.info.configured } ?: requested ?: providers.values.firstOrNull()
                }
                if (provider == null) {
                    callback.onError(requestId, Contract.ERR_BAD_REQUEST, "Provider '$providerId' not found")
                    return@launchRequest
                }
                when (val result = provider.search(query ?: "", offset, kind)) {
                    is ProviderResult.Success -> callback.onItems(requestId, result.items, result.nextOffset)
                    is ProviderResult.Error -> callback.onError(requestId, result.errorCode, result.message)
                }
            }
        }

        override fun trendingKind(requestId: Int, providerId: String?, kind: Int, offset: String?, limit: Int, callback: IGifCallback?) {
            CallerCheck.enforceHost(this@GifEngineService)
            if (callback == null) return
            launchRequest(requestId, callback) {
                val requested = providers[providerId]
                val provider = if (requested?.info?.configured == true) {
                    requested
                } else {
                    providers.values.firstOrNull { it.info.configured } ?: requested ?: providers.values.firstOrNull()
                }
                if (provider == null) {
                    callback.onError(requestId, Contract.ERR_BAD_REQUEST, "Provider '$providerId' not found")
                    return@launchRequest
                }
                when (val result = provider.trending(offset, kind)) {
                    is ProviderResult.Success -> callback.onItems(requestId, result.items, result.nextOffset)
                    is ProviderResult.Error -> callback.onError(requestId, result.errorCode, result.message)
                }
            }
        }

        override fun listPacks(requestId: Int, provider: String?, pos: String?, limit: Int, cb: IGifCallback?) {
            CallerCheck.enforceHost(this@GifEngineService)
            if (cb == null) return
            val target = providers[provider]
            if (target == null) {
                cb.onError(requestId, Contract.ERR_BAD_REQUEST, "Provider '$provider' not found")
                return
            }
            launchRequest(requestId, cb) {
                when (val result = target.listPacks(pos)) {
                    is ProviderResult.Success -> cb.onItems(requestId, result.items, result.nextOffset)
                    is ProviderResult.Error -> cb.onError(requestId, result.errorCode, result.message)
                }
            }
        }

        override fun getPack(requestId: Int, provider: String?, packId: String?, pos: String?, limit: Int, cb: IGifCallback?) {
            CallerCheck.enforceHost(this@GifEngineService)
            if (cb == null) return
            val target = providers[provider]
            if (target == null || packId == null) {
                cb.onError(requestId, Contract.ERR_BAD_REQUEST, "Provider '$provider' or pack not found")
                return
            }
            launchRequest(requestId, cb) {
                when (val result = target.getPack(packId, pos)) {
                    is ProviderResult.Success -> cb.onItems(requestId, result.items, result.nextOffset)
                    is ProviderResult.Error -> cb.onError(requestId, result.errorCode, result.message)
                }
            }
        }

        override fun fetch(requestId: Int, item: GifItem?, callback: IGifCallback?) {
            CallerCheck.enforceHost(this@GifEngineService)
            if (callback == null || item == null) return
            launchRequest(requestId, callback) {
                val provider = providers[item.provider]
                val mediaUrl = provider?.resolveFullMediaUrl(item) ?: item.extra
                if (mediaUrl.isNullOrEmpty()) {
                    callback.onError(requestId, Contract.ERR_OTHER, "Missing media URL")
                    return@launchRequest
                }

                if (mediaUrl.startsWith("/") || mediaUrl.startsWith("file://")) {
                    val filePath = mediaUrl.removePrefix("file://")
                    val localFile = java.io.File(filePath)
                    if (localFile.exists() && localFile.length() >= 32) {
                        val mime = fetchCache.sniffMime(localFile) ?: "image/gif"
                        val pfd = ParcelFileDescriptor.open(localFile, ParcelFileDescriptor.MODE_READ_ONLY)
                        callback.onFetched(requestId, pfd, mime, item.width, item.height)
                        return@launchRequest
                    } else {
                        callback.onError(requestId, Contract.ERR_OTHER, "Local media file not found")
                        return@launchRequest
                    }
                }

                if (fetchCache.hasLowStorage()) {
                    callback.onError(requestId, Contract.ERR_OTHER, "Storage low (< 50MB free)")
                    return@launchRequest
                }

                val result = fetchCache.getOrFetch(item.id, mediaUrl)
                if (result != null) {
                    callback.onFetched(requestId, result.pfd, result.mimeType, item.width, item.height)
                } else {
                    callback.onError(requestId, Contract.ERR_NETWORK, "Failed to download media")
                }
            }
        }

        override fun cancel(requestId: Int) {
            CallerCheck.enforceHost(this@GifEngineService)
            inFlight.remove(requestId)?.cancel()
        }

        override fun hasCredential(provider: String?): Boolean {
            CallerCheck.enforceHost(this@GifEngineService)
            if (provider.isNullOrEmpty()) return false
            runBlocking { credentials.awaitWarm(2000L) }
            return credentials.has(provider)
        }

        override fun setCredential(providerId: String?, field: String?, value: String?): Boolean {
            CallerCheck.enforceHost(this@GifEngineService)
            if (providerId.isNullOrEmpty() || field.isNullOrEmpty() || value == null) return false
            runBlocking { credentials.awaitWarm(2000L) }
            return credentials.set(providerId, field, value)
        }

        override fun clearCredential(providerId: String?) {
            CallerCheck.enforceHost(this@GifEngineService)
            if (providerId.isNullOrEmpty()) return
            runBlocking { credentials.awaitWarm(2000L) }
            credentials.clear(providerId)
        }

        override fun getCredential(providerId: String?, field: String?): String? {
            CallerCheck.enforceHost(this@GifEngineService)
            if (providerId.isNullOrEmpty() || field.isNullOrEmpty()) return null
            runBlocking { credentials.awaitWarm(2000L) }
            return credentials.get(providerId, field)
        }
    }

    private fun launchRequest(requestId: Int, callback: IGifCallback, block: suspend () -> Unit) {
        if (inFlight.size >= MAX_IN_FLIGHT) {
            try {
                callback.onError(requestId, Contract.ERR_RATE_LIMITED, "Engine busy, max concurrent requests reached")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send busy error", e)
            }
            return
        }

        val job = serviceScope.launch {
            try {
                val completed = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                    block()
                    true
                }
                if (completed == null) {
                    callback.onError(requestId, Contract.ERR_TIMEOUT, "Request timed out")
                }
            } catch (_: CancellationException) {
                // Request cancelled by host
            } catch (e: Exception) {
                Log.e(TAG, "Uncaught error executing request $requestId", e)
                try {
                    callback.onError(requestId, Contract.ERR_OTHER, e.message ?: "Unknown error")
                } catch (_: Exception) {}
            } finally {
                inFlight.remove(requestId)
            }
        }
        inFlight[requestId] = job
    }
}
