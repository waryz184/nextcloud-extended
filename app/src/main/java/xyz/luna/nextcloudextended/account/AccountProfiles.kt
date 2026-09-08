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
    val password: String
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
                            add(AccountProfile(item.optString("id", UUID.randomUUID().toString()), url, username, password))
                        }
                    }
                }
            }.getOrDefault(emptyList())
        }

        val url = prefs.getString("server_url", "") ?: ""
        val username = prefs.getString("username", "") ?: ""
        val password = prefs.getString("password", "") ?: ""
        return if (url.isNotBlank() && username.isNotBlank() && password.isNotBlank()) {
            listOf(AccountProfile(UUID.randomUUID().toString(), url, username, password))
        } else emptyList()
    }

    fun save(prefs: SharedPreferences, profile: AccountProfile) {
        val profiles = load(prefs).filterNot { it.id == profile.id || (it.serverUrl == profile.serverUrl && it.username == profile.username) } + profile
        val array = JSONArray()
        profiles.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id)
                put("serverUrl", item.serverUrl)
                put("username", item.username)
                put("password", item.password)
            })
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    fun remove(prefs: SharedPreferences, id: String) {
        val array = JSONArray()
        load(prefs).filterNot { it.id == id }.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id)
                put("serverUrl", item.serverUrl)
                put("username", item.username)
                put("password", item.password)
            })
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }
}
