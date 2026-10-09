package xyz.luna.nextcloudextended.account

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

data class AccountProfile(
    val id: String,
    val serverUrl: String,
    val username: String,
    val password: String,
    /**
     * The server-side account id used in every WebDAV path (`/remote.php/dav/files/<userId>/`). It is
     * resolved from `/ocs/v2.php/cloud/user` at login and can differ from [username] (email logins,
     * LDAP, SSO). Profiles saved by older versions fall back to the login name.
     */
    val userId: String = username
) {
    val label: String
        get() = runCatching { "$username@${URI(serverUrl).host ?: serverUrl}" }.getOrDefault("$username@$serverUrl")
}

object AccountProfiles {
    private const val KEY = "account_profiles"

    fun load(prefs: SharedPreferences): List<AccountProfile> {
        val encoded = prefs.getString(KEY, null)
        if (encoded != null) {
            return runCatching {
                val array = JSONArray(encoded)
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.optJSONObject(index) ?: continue
                        val url = item.optString("serverUrl")
                        val username = item.optString("username")
                        val password = item.optString("password")
                        if (url.isNotBlank() && username.isNotBlank() && password.isNotBlank()) {
                            add(AccountProfile(
                                item.optString("id", UUID.randomUUID().toString()), url, username, password,
                                item.optString("userId").ifBlank { username }
                            ))
                        }
                    }
                }
            }.getOrDefault(emptyList())
        }

        val url = prefs.getString("server_url", "") ?: ""
        val username = prefs.getString("username", "") ?: ""
        val password = prefs.getString("password", "") ?: ""
        val userId = prefs.getString("user_id", "")?.ifBlank { username } ?: username
        return if (url.isNotBlank() && username.isNotBlank() && password.isNotBlank()) {
            listOf(AccountProfile(UUID.randomUUID().toString(), url, username, password, userId))
        } else emptyList()
    }

    fun save(prefs: SharedPreferences, profile: AccountProfile) {
        val profiles = load(prefs).filterNot { it.id == profile.id || (it.serverUrl == profile.serverUrl && it.username == profile.username) } + profile
        write(prefs, profiles)
    }

    fun remove(prefs: SharedPreferences, id: String) {
        write(prefs, load(prefs).filterNot { it.id == id })
    }

    private fun write(prefs: SharedPreferences, profiles: List<AccountProfile>) {
        val array = JSONArray()
        profiles.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id)
                put("serverUrl", item.serverUrl)
                put("username", item.username)
                put("password", item.password)
                put("userId", item.userId)
            })
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }
}
