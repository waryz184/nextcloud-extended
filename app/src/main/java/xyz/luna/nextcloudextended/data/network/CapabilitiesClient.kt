package xyz.luna.nextcloudextended.data.network

import android.os.Handler
import android.os.Looper
import okhttp3.Credentials
import xyz.luna.nextcloudextended.data.model.NextcloudCapabilities
import java.io.IOException

/**
 * Callback facade over [OcsApi.capabilities]. Kept for source compatibility; the parsing lives in
 * [parse] so it stays unit-testable without Android.
 */
class CapabilitiesClient(
    serverUrl: String,
    username: String,
    password: String,
    userId: String = username
) {
    private val session = DavSession(serverUrl, userId, Credentials.basic(username, password))
    private val handler = Handler(Looper.getMainLooper())

    fun getCapabilities(onSuccess: (NextcloudCapabilities) -> Unit, onFailure: (Exception) -> Unit) {
        NextcloudHttp.ioExecutor.execute {
            try {
                val capabilities = OcsApi(session).capabilities()
                handler.post { onSuccess(capabilities) }
            } catch (error: Exception) {
                handler.post { onFailure(error as? IOException ?: IOException(error.message, error)) }
            }
        }
    }

    companion object {
        fun parse(json: String): NextcloudCapabilities {
            val keys = Regex("\\\"([A-Za-z0-9_.-]+)\\\"\\s*:")
                .findAll(json)
                .map { it.groupValues[1].lowercase() }
                .toSet()
            val version = Regex("\\\"string\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                .find(json)?.groupValues?.get(1)
            val base = NextcloudCapabilities(true, version, keys)
            return runCatching { withDetails(base, json) }.getOrDefault(base)
        }

        private fun withDetails(base: NextcloudCapabilities, json: String): NextcloudCapabilities {
            val root = org.json.JSONObject(json)
            val data = root.optJSONObject("ocs")?.optJSONObject("data") ?: root.optJSONObject("data") ?: return base
            val caps = data.optJSONObject("capabilities") ?: return base
            val files = caps.optJSONObject("files")
            val sharing = caps.optJSONObject("files_sharing")
            val passwordPolicy = sharing?.optJSONObject("public")?.optJSONObject("password")
            return base.copy(
                chunkedUploadMaxSize = files?.optJSONObject("chunked_upload")?.optLong("max_size", 0) ?: 0L,
                versioning = files?.optBoolean("versioning", false) ?: false,
                trashbin = files?.optBoolean("undelete", false) ?: false,
                bigFileChunking = files?.optBoolean("bigfilechunking", true) ?: true,
                comments = files?.optBoolean("comments", false) ?: false,
                sharingEnabled = sharing?.optBoolean("api_enabled", true) ?: true,
                linkPasswordEnforced = passwordPolicy?.optJSONObject("enforced_for")?.let {
                    it.optBoolean("read_only") || it.optBoolean("read_write") || it.optBoolean("upload_only")
                } ?: (passwordPolicy?.optBoolean("enforced", false) ?: false)
            )
        }
    }
}
