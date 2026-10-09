package xyz.luna.nextcloudextended.data.network

import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import xyz.luna.nextcloudextended.data.model.ServerStatus
import java.io.IOException
import java.util.Locale

/** The host answered, but it is not (yet) a usable Nextcloud: wrong URL, proxy page, install pending. */
class NotNextcloudException(message: String) : IOException(message)

/** status.php answered 400/code 15: the server's `trusted_domains` does not list this host name. */
class UntrustedDomainException(val host: String) : IOException("Untrusted domain: $host")

/**
 * Turns whatever the user pasted into the server root: adds the scheme, drops `/index.php/...`,
 * `/remote.php/dav...`, `/apps/files`, `/login`, query strings and trailing slashes — while keeping
 * a real sub-folder (`https://host/nextcloud`) intact.
 */
fun normalizeServerInput(raw: String, defaultScheme: String = "https"): String {
    var url = raw.trim()
    if (url.isEmpty()) return url
    if (!Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://.*").matches(url)) url = "$defaultScheme://$url"
    // lower-case the scheme only; paths are case sensitive
    val scheme = url.substringBefore("://").lowercase(Locale.ROOT)
    url = scheme + "://" + url.substringAfter("://")
    url = url.substringBefore('#').substringBefore('?')
    val cut = Regex("(?i)/(index\\.php|remote\\.php|ocs|apps/|login|status\\.php|s/|f/).*$")
    url = url.replace(cut, "")
    return url.trimEnd('/')
}

/** Everything that talks to a server before there are credentials. */
object ServerAccess {

    /**
     * Calls `status.php`, following redirects (a `www.` or http→https hop changes the base URL and
     * the caller must store the *final* one, otherwise every later request pays a redirect).
     */
    fun probe(rawUrl: String, allowHttp: Boolean = false): ServerStatus {
        val normalized = normalizeServerInput(rawUrl)
        val session = DavSession(normalized, "", null, allowInsecureHttp = allowHttp)
        val response = try {
            session.call(session.request("/status.php").get().build(), CallOptions(accept = setOf(400, 401, 403, 404)))
        } catch (e: HttpStatusException) {
            if (e.kind == HttpStatusException.Kind.MAINTENANCE) {
                return ServerStatus(normalized, installed = true, maintenance = true, needsDbUpgrade = false, version = null, productName = null)
            }
            throw e
        }
        response.use {
            val body = it.body?.string().orEmpty()
            val finalBase = it.request.url.toString().substringBefore("/status.php").trimEnd('/')
            if (it.code == 400 && Regex("\"code\"\\s*:\\s*15\\b").containsMatchIn(body)) {
                throw UntrustedDomainException(it.request.url.host)
            }
            if (!it.isSuccessful) throw NotNextcloudException("No Nextcloud found at $normalized (HTTP ${it.code})")
            val json = try { JSONObject(body) } catch (e: JSONException) {
                throw NotNextcloudException("The address does not look like a Nextcloud server")
            }
            if (!json.has("installed") && !json.has("version")) throw NotNextcloudException("The address does not look like a Nextcloud server")
            return ServerStatus(
                baseUrl = finalBase,
                installed = json.optBoolean("installed", true),
                maintenance = json.optBoolean("maintenance", false),
                needsDbUpgrade = json.optBoolean("needsDbUpgrade", false),
                version = json.optString("versionstring").ifBlank { json.optString("version") }.takeIf { v -> v.isNotBlank() },
                productName = json.optString("productname").takeIf { v -> v.isNotBlank() }
            )
        }
    }
}

/**
 * Nextcloud "Login flow v2": the user signs in in a browser (so 2FA, SSO and WebAuthn just work) and
 * the app receives a dedicated app password. This is how the official client logs in.
 */
class LoginFlowV2(rawServerUrl: String, private val allowHttp: Boolean = false) {
    class Start(val loginUrl: String, val pollEndpoint: String, val pollToken: String)
    class Result(val server: String, val loginName: String, val appPassword: String)

    private val baseUrl = normalizeServerInput(rawServerUrl)
    private val session = DavSession(baseUrl, "", null, allowInsecureHttp = allowHttp)

    fun start(): Start {
        val request = session.request("/index.php/login/v2").post(FormBody.Builder().build()).build()
        val body = session.call(request, CallOptions(maxAttempts = 1)).use { it.body?.string().orEmpty() }
        try {
            val json = JSONObject(body)
            val poll = json.getJSONObject("poll")
            return Start(
                loginUrl = secure(json.getString("login")),
                pollEndpoint = secure(poll.getString("endpoint")),
                pollToken = poll.getString("token")
            )
        } catch (e: JSONException) {
            throw UnexpectedResponseException("The server does not support the browser login", e)
        }
    }

    /** One poll. Returns null while the user has not finished (the server answers 404 until then). */
    fun poll(start: Start): Result? {
        val request = Request.Builder().url(start.pollEndpoint)
            .post(FormBody.Builder().add("token", start.pollToken).build()).build()
        val response = session.call(request, CallOptions(accept = setOf(404), maxAttempts = 1))
        response.use {
            if (it.code == 404) return null
            val json = try { JSONObject(it.body?.string().orEmpty()) } catch (e: JSONException) {
                throw UnexpectedResponseException("Unreadable login response", e)
            }
            val password = json.optString("appPassword")
            val login = json.optString("loginName")
            if (password.isBlank() || login.isBlank()) throw UnexpectedResponseException("The login response is incomplete")
            return Result(json.optString("server").ifBlank { baseUrl }.trimEnd('/'), login, password)
        }
    }

    /**
     * Blocks until the user finished or [timeoutMs] elapsed (the server invalidates the token after
     * 20 minutes anyway). Polls gently: the official client waits 30s, which feels broken, while a
     * tight loop is rude to the server.
     */
    fun awaitResult(
        start: Start,
        timeoutMs: Long = 20 * 60_000L,
        intervalMs: Long = 2_500L,
        isCancelled: () -> Boolean = { false },
        sleeper: (Long) -> Unit = { Thread.sleep(it) }
    ): Result? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isCancelled()) return null
            try {
                poll(start)?.let { return it }
            } catch (e: IOException) {
                // A flaky connection while the user is typing their password must not abort the login.
                if (!e.isTransientFailure()) throw e
            }
            sleeper(intervalMs)
        }
        return null
    }

    /** Some reverse proxies leave the server believing it speaks http: upgrade links on our own host. */
    private fun secure(url: String): String {
        val base = baseUrl.toHttpUrlOrNull() ?: return url
        if (!base.isHttps || !url.startsWith("http://", ignoreCase = true)) return url
        val parsed = url.toHttpUrlOrNull() ?: return url
        return if (parsed.host.equals(base.host, ignoreCase = true)) url.replaceFirst(Regex("^http://", RegexOption.IGNORE_CASE), "https://") else url
    }
}
