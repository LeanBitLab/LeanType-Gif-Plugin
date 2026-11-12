package com.leanbitlab.leantype.gif.security

import android.content.Context
import android.os.Binder
import android.os.Process
import android.os.SystemClock
import com.leanbitlab.leantype.gif.BuildConfig
import com.leanbitlab.leantype.gif.SigningCerts
import java.util.concurrent.ConcurrentHashMap

object CallerCheck {
    private const val UID_CACHE_TTL_MS = 5_000L
    private val verifiedUids = ConcurrentHashMap<Int, Long>()

    private val ALLOWED_PACKAGES = setOf(
        "com.leanbitlab.leantype",
        "com.leanbitlab.leantype.offline",
        "com.leanbitlab.leantype.debug",
        "com.leanbitlab.leantype.offline.debug"
    )

    private val PINNED_CERTS = setOf(
        "ac7360ae311a36f2cc1caf1dad4848e18e191c55aaf128838fc34c92c6838016", // release
        "ca2d7663d16db859ee3ec70b349f0ab232d2e16eefdd4f4eff8b451ba694509e", // debug
        "01cfc72120c539ec4cc846cd70d25f83e3fc694d8bdcd2211454a8c993c4f5a3"  // plugin
    )

    fun enforceHost(context: Context) {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) {
            return
        }
        val now = SystemClock.elapsedRealtime()
        val cachedUntil = verifiedUids[uid]
        if (cachedUntil != null && now < cachedUntil) {
            return
        }

        val pm = context.packageManager
        val packages = pm.getPackagesForUid(uid) ?: throw SecurityException("Caller UID has no packages")

        val allowedHost = packages.firstOrNull { it in ALLOWED_PACKAGES }
            ?: throw SecurityException("Caller package '${packages.joinToString()}' is not an authorized LeanType host")

        val certs = SigningCerts.sha256Digests(pm, allowedHost)
        val valid = certs.any { it in PINNED_CERTS } || (BuildConfig.DEBUG && certs.any { it in SigningCerts.sha256Digests(pm, context.packageName) })
        if (!valid) {
            verifiedUids.remove(uid)
            throw SecurityException("Caller certificate is not trusted for '$allowedHost'")
        }
        verifiedUids[uid] = now + UID_CACHE_TTL_MS
    }
}
