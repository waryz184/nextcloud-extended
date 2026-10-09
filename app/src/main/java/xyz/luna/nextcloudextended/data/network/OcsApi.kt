package xyz.luna.nextcloudextended.data.network

import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import xyz.luna.nextcloudextended.data.model.ActivityItem
import xyz.luna.nextcloudextended.data.model.NextcloudCapabilities
import xyz.luna.nextcloudextended.data.model.NextcloudNotification
import xyz.luna.nextcloudextended.data.model.NextcloudShare
import xyz.luna.nextcloudextended.data.model.NotificationAction
import xyz.luna.nextcloudextended.data.model.Sharee
import xyz.luna.nextcloudextended.data.model.UserInfo

/** OCS (`/ocs/v2.php`) endpoints, blocking. v2 maps the OCS status onto the HTTP status. */
class OcsApi(val session: DavSession) {

    // ── Account ─────────────────────────────────────────────────────────────────────────────

    fun currentUser(): UserInfo {
        val data = getJson("/ocs/v2.php/cloud/user") as? JSONObject
            ?: throw UnexpectedResponseException("Missing user data")
        val quota = data.optJSONObject("quota")
        return UserInfo(
            id = data.optString("id").ifBlank { throw UnexpectedResponseException("The server did not report a user id") },
            displayName = data.optString("display-name").ifBlank { data.optString("displayname") }.ifBlank { data.optString("id") },
            email = data.optString("email").takeIf { it.isNotBlank() && it != "null" },
            quotaUsed = quota?.optLong("used", 0) ?: 0,
            quotaTotal = quota?.optLong("total", -3) ?: -3,
            quotaFree = quota?.optLong("free", -3) ?: -3,
            quotaRelative = quota?.optDouble("relative", 0.0) ?: 0.0
        )
    }

    fun capabilities(): NextcloudCapabilities {
        val request = ocs("/ocs/v2.php/cloud/capabilities").get().build()
        val body = session.call(request).use { it.body?.string().orEmpty() }
        return CapabilitiesClient.parse(body)
    }

    /**
     * Revokes the app password this session authenticates with (only valid for app passwords). Used on
     * logout so a token created by Login Flow does not linger in "Devices & sessions".
     */
    fun revokeAppPassword() {
        val request = ocs("/ocs/v2.php/core/apppassword").delete().build()
        session.call(request, CallOptions(accept = setOf(401, 403, 404), maxAttempts = 1)).close()
    }

    // ── Sharing ─────────────────────────────────────────────────────────────────────────────

    /** Shares of [path] (`/remote.php/dav/files/<user>/...` form is accepted and converted). */
    fun shares(path: String, includeReshares: Boolean = true): List<NextcloudShare> {
        val data = getJson("/ocs/v2.php/apps/files_sharing/api/v1/shares",
            "path" to sharePath(path), "reshares" to includeReshares.toString())
        return jsonArray(data).mapNotNull { parseShare(it as? JSONObject) }
    }

    fun createShare(
        path: String,
        shareType: Int,
        shareWith: String? = null,
        permissions: Int? = null,
        password: String? = null,
        expireDate: String? = null,
        note: String? = null,
        label: String? = null,
        hideDownload: Boolean? = null
    ): NextcloudShare {
        val form = FormBody.Builder().add("path", sharePath(path)).add("shareType", shareType.toString())
        shareWith?.let { form.add("shareWith", it) }
        permissions?.let { form.add("permissions", it.toString()) }
        password?.takeIf { it.isNotEmpty() }?.let { form.add("password", it) }
        expireDate?.takeIf { it.isNotEmpty() }?.let { form.add("expireDate", it) }
        note?.takeIf { it.isNotEmpty() }?.let { form.add("note", it) }
        label?.takeIf { it.isNotEmpty() }?.let { form.add("label", it) }
        hideDownload?.let { form.add("hideDownload", it.toString()) }
        val request = ocs("/ocs/v2.php/apps/files_sharing/api/v1/shares").post(form.build()).build()
        // Creating twice must never silently produce two links, so POST is not retried.
        val data = call(request, CallOptions(maxAttempts = 1))
        return parseShare(data as? JSONObject) ?: throw UnexpectedResponseException("The server returned an unreadable share")
    }

    /** Pass `null` to leave a field untouched; empty string clears password/expiry/note. */
    fun updateShare(
        id: String,
        permissions: Int? = null,
        password: String? = null,
        expireDate: String? = null,
        note: String? = null,
        label: String? = null,
        hideDownload: Boolean? = null
    ): NextcloudShare? {
        val form = FormBody.Builder()
        permissions?.let { form.add("permissions", it.toString()) }
        password?.let { form.add("password", it) }
        expireDate?.let { form.add("expireDate", it) }
        note?.let { form.add("note", it) }
        label?.let { form.add("label", it) }
        hideDownload?.let { form.add("hideDownload", it.toString()) }
        val request = ocs("/ocs/v2.php/apps/files_sharing/api/v1/shares/$id").put(form.build()).build()
        return parseShare(call(request) as? JSONObject)
    }

    /** Deleting an already deleted share is a success. */
    fun deleteShare(id: String) {
        val request = ocs("/ocs/v2.php/apps/files_sharing/api/v1/shares/$id").delete().build()
        session.call(request, CallOptions(accept = setOf(404))).close()
    }

    fun searchSharees(query: String, limit: Int = 15): List<Sharee> {
        if (query.isBlank()) return emptyList()
        val data = getJson("/ocs/v2.php/apps/files_sharing/api/v1/sharees",
            "search" to query, "itemType" to "file", "perPage" to limit.toString(), "lookup" to "false") as? JSONObject
            ?: return emptyList()
        val result = mutableListOf<Sharee>()
        fun collect(bucket: JSONObject?, key: String) {
            val array = bucket?.optJSONArray(key) ?: return
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val value = item.optJSONObject("value") ?: continue
                result.add(Sharee(
                    label = item.optString("label").ifBlank { value.optString("shareWith") },
                    shareType = value.optInt("shareType"),
                    shareWith = value.optString("shareWith"),
                    subline = item.optString("shareWithDisplayNameUnique").takeIf { it.isNotBlank() }
                ))
            }
        }
        for (bucket in listOf("exact", null)) {
            val root = if (bucket == null) data else data.optJSONObject(bucket)
            listOf("users", "groups", "emails", "remotes", "circles").forEach { collect(root, it) }
        }
        return result.distinctBy { it.shareType to it.shareWith }
    }

    // ── Notifications ───────────────────────────────────────────────────────────────────────

    fun notifications(): List<NextcloudNotification> {
        val data = getJson("/ocs/v2.php/apps/notifications/api/v2/notifications")
        return jsonArray(data).mapNotNull { item ->
            val o = item as? JSONObject ?: return@mapNotNull null
            val actions = o.optJSONArray("actions")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)?.let { a ->
                        NotificationAction(a.optString("label"), a.optString("link"), a.optString("type", "POST"), a.optBoolean("primary"))
                    }
                }
            }.orEmpty()
            NextcloudNotification(
                id = o.optLong("notification_id"),
                app = o.optString("app"),
                datetime = o.optString("datetime"),
                subject = o.optString("subject"),
                message = o.optString("message"),
                link = o.optString("link").takeIf { it.isNotBlank() },
                actions = actions
            )
        }
    }

    fun deleteNotification(id: Long) {
        session.call(ocs("/ocs/v2.php/apps/notifications/api/v2/notifications/$id").delete().build(), CallOptions(accept = setOf(404))).close()
    }

    fun deleteAllNotifications() {
        session.call(ocs("/ocs/v2.php/apps/notifications/api/v2/notifications").delete().build(), CallOptions(accept = setOf(404))).close()
    }

    /** Executes a notification action (accept/decline a share, …). */
    fun runNotificationAction(action: NotificationAction) {
        val builder = Request.Builder().url(action.link).header("OCS-APIRequest", "true").header("Accept", "application/json")
        val request = when (action.method.uppercase()) {
            "DELETE" -> builder.delete().build()
            "PUT" -> builder.put(FormBody.Builder().build()).build()
            "GET" -> builder.get().build()
            else -> builder.post(FormBody.Builder().build()).build()
        }
        session.call(request, CallOptions(maxAttempts = 1)).close()
    }

    // ── Activity ────────────────────────────────────────────────────────────────────────────

    fun activity(limit: Int = 50, sinceId: Long? = null, objectType: String? = null, objectId: Long? = null): List<ActivityItem> {
        val params = mutableListOf("limit" to limit.toString(), "sort" to "desc")
        sinceId?.let { params.add("since" to it.toString()) }
        if (objectType != null && objectId != null) {
            params.add("object_type" to objectType); params.add("object_id" to objectId.toString())
        }
        val data = try {
            getJson("/ocs/v2.php/apps/activity/api/v2/activity/all", *params.toTypedArray())
        } catch (e: HttpStatusException) {
            if (e.code == 304) return emptyList() else throw e
        }
        return jsonArray(data).mapNotNull { item ->
            val o = item as? JSONObject ?: return@mapNotNull null
            ActivityItem(
                id = o.optLong("activity_id"),
                app = o.optString("app"),
                type = o.optString("type"),
                datetime = o.optString("datetime"),
                subject = o.optString("subject"),
                message = o.optString("message"),
                objectType = o.optString("object_type").takeIf { it.isNotBlank() },
                objectId = o.optLong("object_id", -1).takeIf { it >= 0 },
                objectName = o.optString("object_name").takeIf { it.isNotBlank() },
                link = o.optString("link").takeIf { it.isNotBlank() }
            )
        }
    }

    // ── Direct editing (Collabora / OnlyOffice via the Files app) ───────────────────────────

    fun directEditingUrl(path: String): String {
        val form = FormBody.Builder().add("path", sharePath(path)).build()
        val request = ocs("/ocs/v2.php/apps/files/api/v1/directEditing/open").post(form).build()
        val data = call(request, CallOptions(maxAttempts = 1)) as? JSONObject
        return data?.optString("url")?.takeIf { it.isNotBlank() }
            ?: throw UnexpectedResponseException("Collabora/OnlyOffice not available on this server")
    }

    // ── plumbing ────────────────────────────────────────────────────────────────────────────

    private fun ocs(path: String, vararg query: Pair<String, String>): Request.Builder {
        val url = session.url(path).toHttpUrl().newBuilder().apply {
            addQueryParameter("format", "json")
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        return Request.Builder().url(url).header("OCS-APIRequest", "true").header("Accept", "application/json")
    }

    private fun getJson(path: String, vararg query: Pair<String, String>): Any? =
        call(ocs(path, *query).get().build())

    private fun call(request: Request, options: CallOptions = CallOptions()): Any? =
        session.call(request, options).use { parseOcs(it.body?.string().orEmpty()) }

    private fun jsonArray(data: Any?): List<Any?> = when (data) {
        is JSONArray -> (0 until data.length()).map { data.opt(it) }
        // Old servers (and the XML→JSON bridge) wrap lists as {"element": [...]}.
        is JSONObject -> data.optJSONArray("element")?.let { jsonArray(it) } ?: emptyList()
        else -> emptyList()
    }

    private fun parseShare(o: JSONObject?): NextcloudShare? {
        if (o == null) return null
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
        return NextcloudShare(
            id = id,
            shareType = o.optInt("share_type"),
            shareWith = o.optString("share_with").takeIf { it.isNotBlank() && it != "null" },
            url = o.optString("url").takeIf { it.isNotBlank() && it != "null" },
            permissions = o.optInt("permissions"),
            expiration = o.optString("expiration").takeIf { it.isNotBlank() && it != "null" },
            shareWithDisplayName = o.optString("share_with_displayname").takeIf { it.isNotBlank() && it != "null" },
            note = o.optString("note").takeIf { it.isNotBlank() && it != "null" },
            label = o.optString("label").takeIf { it.isNotBlank() && it != "null" },
            passwordProtected = o.optString("password").isNotBlank() && o.optString("password") != "null" || o.optBoolean("password_protected"),
            hideDownload = o.optBoolean("hide_download"),
            path = o.optString("path").takeIf { it.isNotBlank() },
            ownerDisplayName = o.optString("displayname_owner").takeIf { it.isNotBlank() }
        )
    }

    companion object {
        /** `/remote.php/dav/files/alice/Docs/a.txt` → `/Docs/a.txt` (what the OCS Share API expects). */
        fun sharePath(davPath: String): String {
            val stripped = davPath.replaceFirst(Regex("^/remote\\.php/(?:dav/files|webdav)/[^/]+"), "")
            val normalized = if (stripped.startsWith("/")) stripped else "/$stripped"
            return if (normalized.length > 1) normalized.trimEnd('/') else normalized
        }

        /** Returns `ocs.data`; raises [HttpStatusException] for an OCS failure even if HTTP said 200. */
        fun parseOcs(body: String): Any? {
            val root = try { JSONObject(body) } catch (e: JSONException) {
                throw UnexpectedResponseException("The server did not answer with an OCS document", e)
            }
            val ocs = root.optJSONObject("ocs") ?: root
            val meta = ocs.optJSONObject("meta")
            val code = meta?.optInt("statuscode", 200) ?: 200
            if (code != 200 && code != 100 && code != 201) {
                val status = if (code in 400..599) code else if (code == 997) 401 else 400
                throw HttpStatusException(status, davMessage = meta?.optString("message")?.takeIf { it.isNotBlank() })
            }
            return ocs.opt("data")
        }
    }
}
