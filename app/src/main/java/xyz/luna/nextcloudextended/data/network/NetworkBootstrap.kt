package xyz.luna.nextcloudextended.data.network

import android.content.Context
import android.os.Build

/**
 * One call from `Application.onCreate` (it runs in every process: UI, WorkManager, the contacts sync
 * service), so all of them share the same TLS trust decisions and identify themselves identically.
 */
object NetworkBootstrap {
    fun install(context: Context) {
        val app = context.applicationContext
        TlsTrust.known = KnownCertificates(app.getSharedPreferences("known_certificates", Context.MODE_PRIVATE))
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "dev"
        NextcloudHttp.configureUserAgent(version.orEmpty(), Build.MODEL.orEmpty())
    }
}
