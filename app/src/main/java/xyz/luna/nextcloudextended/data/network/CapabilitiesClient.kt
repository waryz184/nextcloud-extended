package xyz.luna.nextcloudextended.data.network

import android.os.Handler
import android.os.Looper
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import xyz.luna.nextcloudextended.data.model.NextcloudCapabilities
import java.io.IOException
import java.util.concurrent.TimeUnit

class CapabilitiesClient(
    serverUrl: String,
    username: String,
    password: String
) {
    private val baseUrl = serverUrl.trimEnd('/')
    private val auth = Credentials.basic(username, password)
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val handler = Handler(Looper.getMainLooper())

    fun getCapabilities(onSuccess: (NextcloudCapabilities) -> Unit, onFailure: (Exception) -> Unit) {
        val request = Request.Builder()
            .url("$baseUrl/ocs/v2.php/cloud/capabilities")
            .addHeader("Authorization", auth)
            .addHeader("OCS-APIRequest", "true")
            .addHeader("Accept", "application/json")
            .get()
            .build()
        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                handler.post { onFailure(e) }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.use {
                    if (!it.isSuccessful) {
                        handler.post { onFailure(IOException("HTTP Error: ${it.code}")) }
                        return
                    }
                    try {
                        val body = it.body?.string() ?: throw IOException("Empty capabilities response")
                        handler.post { onSuccess(parse(body)) }
                    } catch (error: Exception) {
                        handler.post { onFailure(error) }
                    }
                }
            }
        })
    }

    companion object {
        fun parse(json: String): NextcloudCapabilities {
            val keys = Regex("\\\"([A-Za-z0-9_.-]+)\\\"\\s*:")
                .findAll(json)
                .map { it.groupValues[1].lowercase() }
                .toSet()
            val version = Regex("\\\"string\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                .find(json)?.groupValues?.get(1)
            return NextcloudCapabilities(true, version, keys)
        }
    }
}
