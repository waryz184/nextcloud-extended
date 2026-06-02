package xyz.luna.nextcloudextended.data.network

import xyz.luna.nextcloudextended.data.model.CalendarEvent
import xyz.luna.nextcloudextended.data.model.CalendarInfo
import xyz.luna.nextcloudextended.data.model.NextcloudTask
import xyz.luna.nextcloudextended.data.model.NextcloudNote
import xyz.luna.nextcloudextended.data.model.NextcloudFile
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import android.os.Handler
import android.os.Looper

class CalDavClient(
    private val serverUrl: String,
    private val username: String,
    private val password: String
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val credentials = Credentials.basic(username, password)
    private val activeCalls = java.util.concurrent.CopyOnWriteArrayList<okhttp3.Call>()

    val baseUrl: String get() = if (serverUrl.endsWith("/")) serverUrl.dropLast(1) else serverUrl
    fun getAuthorizationHeader(): String = credentials
    fun buildFileUrl(fileHref: String) = "$baseUrl$fileHref"

    private val mainHandler = Handler(Looper.getMainLooper())
    private fun runOnMain(action: () -> Unit) { mainHandler.post(action) }

    fun cancelAll() {
        activeCalls.toList().forEach { it.cancel() }
        activeCalls.clear()
    }

    private fun okhttp3.Call.enqueueTracked(callback: okhttp3.Callback) {
        activeCalls.add(this)
        this.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                activeCalls.remove(call)
                callback.onFailure(call, e)
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                activeCalls.remove(call)
                callback.onResponse(call, response)
            }
        })
    }

    // Fetches all calendars in one PROPFIND — returns event calendars (with color) + task lists
    fun getAllCalendarData(
        onSuccess: (eventCalendars: List<CalendarInfo>, taskLists: List<Pair<String, String>>) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val url = "$baseUrl/remote.php/dav/calendars/$username/"

        val propfindBody = """<?xml version="1.0" encoding="utf-8" ?>
<d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav" xmlns:cs="http://apple.com/ns/ical/">
  <d:prop>
    <d:displayname />
    <d:resourcetype />
    <c:supported-calendar-component-set />
    <cs:calendar-color />
  </d:prop>
</d:propfind>""".trimIndent()

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", credentials)
            .addHeader("Depth", "1")
            .addHeader("Content-Type", "application/xml; charset=utf-8")
            .method("PROPFIND", propfindBody.toRequestBody("application/xml".toMediaType()))
            .build()

        client.newCall(request).enqueueTracked(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                runOnMain { onFailure(e) }
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (!response.isSuccessful) {
                    runOnMain { onFailure(Exception("HTTP Error: ${response.code}")) }
                    return
                }
                val body = response.body?.string() ?: ""
                try {
                    val eventCals = parseCalendarsWithColor(body, "VEVENT")
                    val taskLists = parseCalendarsWithColor(body, "VTODO").map { Pair(it.href, it.displayName) }
                    runOnMain { onSuccess(eventCals, taskLists) }
                } catch (e: Exception) {
                    runOnMain { onFailure(e) }
                }
            }
        })
    }

    fun getCalendars(onSuccess: (List<CalendarInfo>) -> Unit, onFailure: (Exception) -> Unit) {
        getAllCalendarData(onSuccess = { eventCals, _ -> onSuccess(eventCals) }, onFailure = onFailure)
    }

    fun getTaskLists(onSuccess: (List<Pair<String, String>>) -> Unit, onFailure: (Exception) -> Unit) {
        getAllCalendarData(onSuccess = { _, taskLists -> onSuccess(taskLists) }, onFailure = onFailure)
    }

    // Fetch all VEVENTs in a calendar — tags each event with calendarHref for multi-cal support
    fun getEvents(calendarHref: String, onSuccess: (List<CalendarEvent>) -> Unit, onFailure: (Exception) -> Unit) {
        val url = "$baseUrl$calendarHref"

        val reportBody = """<?xml version="1.0" encoding="utf-8" ?>
<c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
  <d:prop>
    <c:calendar-data />
  </d:prop>
  <c:filter>
    <c:comp-filter name="VCALENDAR">
      <c:comp-filter name="VEVENT" />
    </c:comp-filter>
  </c:filter>
</c:calendar-query>""".trimIndent()

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", credentials)
            .addHeader("Depth", "1")
            .addHeader("Content-Type", "application/xml; charset=utf-8")
            .method("REPORT", reportBody.toRequestBody("application/xml".toMediaType()))
            .build()

        client.newCall(request).enqueueTracked(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                runOnMain { onFailure(e) }
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (!response.isSuccessful) {
                    runOnMain { onFailure(Exception("HTTP Error: ${response.code}")) }
                    return
                }
                val body = response.body?.string() ?: ""
                try {
                    val events = parseEventsFromReport(body, calendarHref)
                    runOnMain { onSuccess(events) }
                } catch (e: Exception) {
                    runOnMain { onFailure(e) }
                }
            }
        })
    }

    fun getTasks(calendarHref: String, onSuccess: (List<NextcloudTask>) -> Unit, onFailure: (Exception) -> Unit) {
        val url = "$baseUrl$calendarHref"

        val reportBody = """<?xml version="1.0" encoding="utf-8" ?>
<c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
  <d:prop>
    <c:calendar-data />
  </d:prop>
  <c:filter>
    <c:comp-filter name="VCALENDAR">
      <c:comp-filter name="VTODO" />
    </c:comp-filter>
  </c:filter>
</c:calendar-query>""".trimIndent()

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", credentials)
            .addHeader("Depth", "1")
            .addHeader("Content-Type", "application/xml; charset=utf-8")
            .method("REPORT", reportBody.toRequestBody("application/xml".toMediaType()))
            .build()

        client.newCall(request).enqueueTracked(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                runOnMain { onFailure(e) }
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (!response.isSuccessful) {
                    runOnMain { onFailure(Exception("HTTP Error: ${response.code}")) }
                    return
                }
                val body = response.body?.string() ?: ""
                try {
                    val tasks = parseTasksFromReport(body, calendarHref)
                    runOnMain { onSuccess(tasks) }
                } catch (e: Exception) {
                    runOnMain { onFailure(e) }
                }
            }
        })
    }

    fun saveTask(task: NextcloudTask, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val fileUrl = if (task.calendarHref.endsWith("/")) "$baseUrl${task.calendarHref}${task.uid}.ics"
                      else "$baseUrl${task.calendarHref}/${task.uid}.ics"

        val cleanSummary = task.summary.replace("\n", " ").replace("\r", "")
        val cleanDescription = task.description?.replace("\n", "\\n")?.replace("\r", "")

        val icsBody = buildString {
            appendLine("BEGIN:VCALENDAR")
            appendLine("VERSION:2.0")
            appendLine("PRODID:-//Nextcloud Extended//Tasks//EN")
            appendLine("BEGIN:VTODO")
            appendLine("UID:${task.uid}")
            appendLine("SUMMARY:$cleanSummary")
            if (cleanDescription != null) appendLine("DESCRIPTION:$cleanDescription")
            appendLine("STATUS:${task.status}")
            if (task.due != null) {
                appendLine("DUE:${task.due.replace("-", "").replace(" ", "T").replace(":", "")}Z")
            }
            appendLine("END:VTODO")
            append("END:VCALENDAR")
        }

        val request = Request.Builder()
            .url(fileUrl)
            .addHeader("Authorization", credentials)
            .addHeader("Content-Type", "text/calendar; charset=utf-8")
            .put(icsBody.toRequestBody("text/calendar; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).enqueueTracked(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) { runOnMain { onFailure(e) } }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful || response.code == 201 || response.code == 204) runOnMain { onSuccess() }
                else runOnMain { onFailure(Exception("HTTP Error: ${response.code}")) }
            }
        })
    }

    fun saveEvent(calendarHref: String, event: CalendarEvent, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val fileUrl = if (calendarHref.endsWith("/")) "$baseUrl$calendarHref${event.id}.ics"
                      else "$baseUrl$calendarHref/${event.id}.ics"

        val cleanSummary = event.summary.replace("\n", " ").replace("\r", "")
        val cleanDescription = event.description?.replace("\n", "\\n")?.replace("\r", "")
        val cleanLocation = event.location?.replace("\n", " ")?.replace("\r", "")
        val startIcs = formatToIcsDate(event.startTime)
        val endIcs = formatToIcsDate(event.endTime)

        val icsBody = buildString {
            appendLine("BEGIN:VCALENDAR")
            appendLine("VERSION:2.0")
            appendLine("PRODID:-//Nextcloud Extended//Calendar//EN")
            appendLine("BEGIN:VEVENT")
            appendLine("UID:${event.id}")
            appendLine("SUMMARY:$cleanSummary")
            if (cleanDescription != null) appendLine("DESCRIPTION:$cleanDescription")
            if (cleanLocation != null) appendLine("LOCATION:$cleanLocation")
            if (startIcs != null) appendLine("DTSTART:$startIcs")
            if (endIcs != null) appendLine("DTEND:$endIcs")
            appendLine("END:VEVENT")
            append("END:VCALENDAR")
        }

        val request = Request.Builder()
            .url(fileUrl)
            .addHeader("Authorization", credentials)
            .addHeader("Content-Type", "text/calendar; charset=utf-8")
            .put(icsBody.toRequestBody("text/calendar; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).enqueueTracked(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) { runOnMain { onFailure(e) } }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful || response.code == 201 || response.code == 204) runOnMain { onSuccess() }
                else runOnMain { onFailure(Exception("HTTP Error: ${response.code}")) }
            }
        })
    }

    fun deleteEvent(calendarHref: String, eventId: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val url = if (calendarHref.endsWith("/")) "$baseUrl$calendarHref$eventId.ics"
                  else "$baseUrl$calendarHref/$eventId.ics"
        val request = Request.Builder().url(url).addHeader("Authorization", credentials).delete().build()
        client.newCall(request).enqueueTracked(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) { runOnMain { onFailure(e) } }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful || response.code == 204) runOnMain { onSuccess() }
                else runOnMain { onFailure(Exception("HTTP Error: ${response.code}")) }
            }
        })
    }

    // OCS Shares API — creates a public link (shareType=3) for the given WebDAV path
    fun createShareLink(fileHref: String, onSuccess: (String) -> Unit, onFailure: (Exception) -> Unit) {
        val sharePath = fileHref.replaceFirst(Regex("/remote\\.php/dav/files/[^/]+"), "")
        val url = "$baseUrl/ocs/v2.php/apps/files_sharing/api/v1/shares"
        val formBody = "path=$sharePath&shareType=3"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", credentials)
            .addHeader("OCS-APIRequest", "true")
            .addHeader("Accept", "application/json")
            .post(formBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()

        client.newCall(request).enqueueTracked(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) { runOnMain { onFailure(e) } }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful && response.code != 200) {
                    runOnMain { onFailure(Exception("HTTP Error: ${response.code}")) }
                    return
                }
                try {
                    val json = org.json.JSONObject(body)
                    val url = json.getJSONObject("ocs").getJSONObject("data").getString("url")
                    runOnMain { onSuccess(url) }
                } catch (e: Exception) {
                    runOnMain { onFailure(Exception("Failed to parse share URL")) }
                }
            }
        })
    }

    // OCS Direct Editing API — returns an editor URL for Collabora Online or OnlyOffice
    fun getOnlineEditorUrl(fileHref: String, onSuccess: (String) -> Unit, onFailure: (Exception) -> Unit) {
        val filePath = fileHref.replaceFirst(Regex("/remote\\.php/dav/files/[^/]+"), "")
        val url = "$baseUrl/ocs/v2.php/apps/files/api/v1/directEditing/open"
        val formBody = "path=${java.net.URLEncoder.encode(filePath, "UTF-8")}"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", credentials)
            .addHeader("OCS-APIRequest", "true")
            .addHeader("Accept", "application/json")
            .post(formBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()

        client.newCall(request).enqueueTracked(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) { runOnMain { onFailure(e) } }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    runOnMain { onFailure(Exception("HTTP ${response.code}")) }
                    return
                }
                try {
                    val editUrl = org.json.JSONObject(body)
                        .getJSONObject("ocs").getJSONObject("data").getString("url")
                    runOnMain { onSuccess(editUrl) }
                } catch (e: Exception) {
                    runOnMain { onFailure(Exception("Collabora/OnlyOffice not available on this server")) }
                }
            }
        })
    }

    // Downloads a file's raw bytes for in-app opening
    fun downloadFile(fileHref: String, onSuccess: (ByteArray) -> Unit, onFailure: (Exception) -> Unit) {
        val request = Request.Builder()
            .url("$baseUrl$fileHref")
            .addHeader("Authorization", credentials)
            .get()
            .build()

        client.newCall(request).enqueueTracked(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) { runOnMain { onFailure(e) } }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (!response.isSuccessful) {
                    runOnMain { onFailure(Exception("HTTP Error: ${response.code}")) }
                    return
                }
                val bytes = response.body?.bytes()
                if (bytes == null) runOnMain { onFailure(Exception("Empty response body")) }
                else runOnMain { onSuccess(bytes) }
            }
        })
    }

    fun deleteTask(task: NextcloudTask, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val fileUrl = if (task.calendarHref.endsWith("/")) "$baseUrl${task.calendarHref}${task.uid}.ics"
                      else "$baseUrl${task.calendarHref}/${task.uid}.ics"

        val request = Request.Builder()
            .url(fileUrl)
            .addHeader("Authorization", credentials)
            .delete()
            .build()

        client.newCall(request).enqueueTracked(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) { runOnMain { onFailure(e) } }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful || response.code == 204) runOnMain { onSuccess() }
                else runOnMain { onFailure(Exception("HTTP Error: ${response.code}")) }
            }
        })
    }

    fun getNotes(onSuccess: (List<NextcloudNote>) -> Unit, onFailure: (Exception) -> Unit) {
        val request = Request.Builder()
            .url("$baseUrl/index.php/apps/notes/api/v1/notes")
            .addHeader("Authorization", credentials)
            .addHeader("OCS-APIRequest", "true")
            .addHeader("Accept", "application/json")
            .get().build()

        client.newCall(request).enqueueTracked(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) { runOnMain { onFailure(e) } }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (!response.isSuccessful) { runOnMain { onFailure(Exception("HTTP Error: ${response.code}")) }; return }
                val body = response.body?.string() ?: ""
                try {
                    val notes = mutableListOf<NextcloudNote>()
                    val arr = org.json.JSONArray(body)
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        notes.add(NextcloudNote(o.getInt("id"), o.getString("title"), o.optString("content", ""), o.optString("category", ""), o.optLong("modified", 0L), o.optBoolean("favorite", false)))
                    }
                    runOnMain { onSuccess(notes) }
                } catch (e: Exception) { runOnMain { onFailure(e) } }
            }
        })
    }

    fun createNote(title: String, content: String, category: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val json = org.json.JSONObject().apply { put("title", title); put("content", content); put("category", category) }
        val request = Request.Builder()
            .url("$baseUrl/index.php/apps/notes/api/v1/notes")
            .addHeader("Authorization", credentials)
            .addHeader("OCS-APIRequest", "true")
            .addHeader("Accept", "application/json")
            .post(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).enqueueTracked(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) { runOnMain { onFailure(e) } }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (!response.isSuccessful) runOnMain { onFailure(Exception("HTTP Error: ${response.code}")) }
                else runOnMain { onSuccess() }
            }
        })
    }

    fun updateNote(noteId: Int, title: String, content: String, category: String, favorite: Boolean, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val json = org.json.JSONObject().apply { put("title", title); put("content", content); put("category", category); put("favorite", favorite) }
        val request = Request.Builder()
            .url("$baseUrl/index.php/apps/notes/api/v1/notes/$noteId")
            .addHeader("Authorization", credentials)
            .addHeader("OCS-APIRequest", "true")
            .addHeader("Accept", "application/json")
            .put(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).enqueueTracked(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) { runOnMain { onFailure(e) } }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (!response.isSuccessful) runOnMain { onFailure(Exception("HTTP Error: ${response.code}")) }
                else runOnMain { onSuccess() }
            }
        })
    }

    fun deleteNote(noteId: Int, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val request = Request.Builder()
            .url("$baseUrl/index.php/apps/notes/api/v1/notes/$noteId")
            .addHeader("Authorization", credentials)
            .addHeader("OCS-APIRequest", "true")
            .delete().build()

        client.newCall(request).enqueueTracked(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) { runOnMain { onFailure(e) } }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful || response.code == 204) runOnMain { onSuccess() }
                else runOnMain { onFailure(Exception("HTTP Error: ${response.code}")) }
            }
        })
    }

    fun getFiles(folderPath: String, onSuccess: (List<NextcloudFile>) -> Unit, onFailure: (Exception) -> Unit) {
        val cleanPath = if (folderPath.startsWith("/")) folderPath else "/$folderPath"
        val propfindBody = """<?xml version="1.0" encoding="utf-8" ?>
<d:propfind xmlns:d="DAV:">
  <d:prop>
    <d:displayname /><d:getcontentlength /><d:getlastmodified /><d:resourcetype />
  </d:prop>
</d:propfind>""".trimIndent()

        val request = Request.Builder()
            .url("$baseUrl$cleanPath")
            .addHeader("Authorization", credentials)
            .addHeader("Depth", "1")
            .addHeader("Content-Type", "application/xml; charset=utf-8")
            .method("PROPFIND", propfindBody.toRequestBody("application/xml".toMediaType()))
            .build()

        client.newCall(request).enqueueTracked(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) { runOnMain { onFailure(e) } }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (!response.isSuccessful) { runOnMain { onFailure(Exception("HTTP Error: ${response.code}")) }; return }
                val body = response.body?.string() ?: ""
                try { runOnMain { onSuccess(parseFiles(body, cleanPath)) } }
                catch (e: Exception) { runOnMain { onFailure(e) } }
            }
        })
    }

    fun deleteFile(fileHref: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val request = Request.Builder().url("$baseUrl$fileHref").addHeader("Authorization", credentials).delete().build()
        client.newCall(request).enqueueTracked(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) { runOnMain { onFailure(e) } }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful || response.code == 204) runOnMain { onSuccess() }
                else runOnMain { onFailure(Exception("HTTP Error: ${response.code}")) }
            }
        })
    }

    fun createFolder(parentHref: String, folderName: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val cleanParent = if (parentHref.endsWith("/")) parentHref else "$parentHref/"
        val request = Request.Builder().url("$baseUrl$cleanParent$folderName/").addHeader("Authorization", credentials).method("MKCOL", null).build()
        client.newCall(request).enqueueTracked(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) { runOnMain { onFailure(e) } }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful || response.code == 201) runOnMain { onSuccess() }
                else runOnMain { onFailure(Exception("HTTP Error: ${response.code}")) }
            }
        })
    }

    fun uploadFile(parentHref: String, fileName: String, fileBytes: ByteArray, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val cleanParent = if (parentHref.endsWith("/")) parentHref else "$parentHref/"
        val request = Request.Builder()
            .url("$baseUrl$cleanParent$fileName")
            .addHeader("Authorization", credentials)
            .put(fileBytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()

        client.newCall(request).enqueueTracked(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) { runOnMain { onFailure(e) } }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful || response.code == 201 || response.code == 204) runOnMain { onSuccess() }
                else runOnMain { onFailure(Exception("HTTP Error: ${response.code}")) }
            }
        })
    }

    fun createTaskList(listName: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val listId = UUID.randomUUID().toString()
        val url = "$baseUrl/remote.php/dav/calendars/$username/$listId/"
        val body = """<?xml version="1.0" encoding="utf-8" ?>
<c:mkcalendar xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
  <d:set><d:prop>
    <d:displayname>$listName</d:displayname>
    <c:supported-calendar-component-set><c:comp name="VTODO" /></c:supported-calendar-component-set>
  </d:prop></d:set>
</c:mkcalendar>""".trimIndent()

        val request = Request.Builder().url(url).addHeader("Authorization", credentials)
            .addHeader("Content-Type", "application/xml; charset=utf-8")
            .method("MKCALENDAR", body.toRequestBody("application/xml".toMediaType())).build()

        client.newCall(request).enqueueTracked(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) { runOnMain { onFailure(e) } }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful || response.code == 201) runOnMain { onSuccess() }
                else runOnMain { onFailure(Exception("HTTP Error: ${response.code}")) }
            }
        })
    }

    fun deleteTaskList(calendarHref: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val request = Request.Builder().url("$baseUrl$calendarHref").addHeader("Authorization", credentials).delete().build()
        client.newCall(request).enqueueTracked(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) { runOnMain { onFailure(e) } }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful || response.code == 204) runOnMain { onSuccess() }
                else runOnMain { onFailure(Exception("HTTP Error: ${response.code}")) }
            }
        })
    }

    fun renameTaskList(calendarHref: String, newName: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val body = """<?xml version="1.0" encoding="utf-8" ?>
<d:propertyupdate xmlns:d="DAV:"><d:set><d:prop>
  <d:displayname>$newName</d:displayname>
</d:prop></d:set></d:propertyupdate>""".trimIndent()

        val request = Request.Builder().url("$baseUrl$calendarHref").addHeader("Authorization", credentials)
            .addHeader("Content-Type", "application/xml; charset=utf-8")
            .method("PROPPATCH", body.toRequestBody("application/xml".toMediaType())).build()

        client.newCall(request).enqueueTracked(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) { runOnMain { onFailure(e) } }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful || response.code == 207) runOnMain { onSuccess() }
                else runOnMain { onFailure(Exception("HTTP Error: ${response.code}")) }
            }
        })
    }

    fun renameFile(sourceHref: String, newName: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val isDir = sourceHref.endsWith("/")
        val cleanHref = if (isDir) sourceHref.dropLast(1) else sourceHref
        val parentHref = cleanHref.substring(0, cleanHref.lastIndexOf('/') + 1)
        val encodedNewName = java.net.URLEncoder.encode(newName, "UTF-8").replace("+", "%20")
        val destPath = "$parentHref$encodedNewName" + (if (isDir) "/" else "")

        val request = Request.Builder()
            .url("$baseUrl$sourceHref")
            .addHeader("Authorization", credentials)
            .addHeader("Destination", "$baseUrl$destPath")
            .addHeader("Overwrite", "F")
            .method("MOVE", null).build()

        client.newCall(request).enqueueTracked(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) { runOnMain { onFailure(e) } }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful || response.code == 201 || response.code == 204) runOnMain { onSuccess() }
                else runOnMain { onFailure(Exception("HTTP Error: ${response.code}")) }
            }
        })
    }

    // ── Parsers ────────────────────────────────────────────────────────────────

    private fun parseCalendarsWithColor(xml: String, filterComponent: String): List<CalendarInfo> {
        val result = mutableListOf<CalendarInfo>()
        val responseRegex = Regex("<d:response[\\s\\S]*?</d:response>")
        val hrefRegex = Regex("<d:href>(.*?)</d:href>")
        val displaynameRegex = Regex("<d:displayname>(.*?)</d:displayname>")
        val calendarResourceTypeRegex = Regex("<[a-zA-Z0-9:]*calendar[\\s/>]")
        val compRegex = Regex("""comp\s+name="([^"]+)"""")
        val colorRegex = Regex("""calendar-color[^>]*>\s*#?([0-9a-fA-F]{6,8})\s*<""", RegexOption.IGNORE_CASE)

        for (resp in responseRegex.findAll(xml)) {
            val respStr = resp.value
            val href = hrefRegex.find(respStr)?.groupValues?.get(1) ?: ""
            if (href.isEmpty() || !calendarResourceTypeRegex.containsMatchIn(respStr)) continue
            val supportedComps = compRegex.findAll(respStr).map { it.groupValues[1] }.toSet()
            if (filterComponent !in supportedComps) continue
            val displayname = displaynameRegex.find(respStr)?.groupValues?.get(1) ?: "Calendar"
            val colorHex = colorRegex.find(respStr)?.groupValues?.get(1)?.let { "#${it.take(6)}" } ?: ""
            result.add(CalendarInfo(href, displayname, colorHex))
        }
        return result
    }

    private fun unescapeXml(xml: String): String {
        var r = xml.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        r = Regex("&#([0-9]+);").replace(r) { it.groupValues[1].toInt().toChar().toString() }
        r = Regex("&#x([0-9a-fA-F]+);").replace(r) { it.groupValues[1].toInt(16).toChar().toString() }
        return r
    }

    private fun parseEventsFromReport(xml: String, calendarHref: String): List<CalendarEvent> {
        val events = mutableListOf<CalendarEvent>()
        val calDataRegex = Regex("<[a-zA-Z0-9:]*calendar-data>([\\s\\S]*?)</[a-zA-Z0-9:]*calendar-data>")
        for (m in calDataRegex.findAll(xml)) {
            events.addAll(parseIcs(unescapeXml(m.groupValues[1]), calendarHref))
        }
        return events
    }

    private fun parseTasksFromReport(xml: String, calendarHref: String): List<NextcloudTask> {
        val tasks = mutableListOf<NextcloudTask>()
        val calDataRegex = Regex("<[a-zA-Z0-9:]*calendar-data>([\\s\\S]*?)</[a-zA-Z0-9:]*calendar-data>")
        for (m in calDataRegex.findAll(xml)) {
            tasks.addAll(parseIcsTasks(unescapeXml(m.groupValues[1]), calendarHref))
        }
        return tasks
    }

    private fun parseIcsTasks(icsContent: String, calendarHref: String): List<NextcloudTask> {
        val tasks = mutableListOf<NextcloudTask>()
        val unfolded = icsContent.replace("\r\n ", "").replace("\r\n\t", "").replace("\n ", "").replace("\n\t", "")
        val vtodoRegex = Regex("BEGIN:VTODO[\\s\\S]*?END:VTODO")
        val summaryRegex = Regex("SUMMARY:(.*)")
        val descRegex = Regex("DESCRIPTION:(.*)")
        val statusRegex = Regex("STATUS:(.*)")
        val dueRegex = Regex("DUE(?:;[^:]*)?:(.*)")
        val uidRegex = Regex("UID:(.*)")

        for (match in vtodoRegex.findAll(unfolded)) {
            val s = match.value
            val uid = uidRegex.find(s)?.groupValues?.get(1)?.trim() ?: UUID.randomUUID().toString()
            val summary = summaryRegex.find(s)?.groupValues?.get(1)?.trim() ?: "Untitled task"
            val description = descRegex.find(s)?.groupValues?.get(1)?.trim()?.replace("\\n", "\n")
            val status = statusRegex.find(s)?.groupValues?.get(1)?.trim() ?: "NEEDS-ACTION"
            val due = dueRegex.find(s)?.groupValues?.get(1)?.trim()
            tasks.add(NextcloudTask(uid, summary, description, status, formatIcsDate(due), calendarHref))
        }
        return tasks
    }

    private fun parseIcs(icsContent: String, calendarHref: String): List<CalendarEvent> {
        val events = mutableListOf<CalendarEvent>()
        val unfolded = icsContent.replace("\r\n ", "").replace("\r\n\t", "").replace("\n ", "").replace("\n\t", "")
        val veventRegex = Regex("BEGIN:VEVENT[\\s\\S]*?END:VEVENT")
        val summaryRegex = Regex("SUMMARY:(.*)")
        val descRegex = Regex("DESCRIPTION:(.*)")
        val dtstartRegex = Regex("DTSTART(?:;[^:]*)?:(.*)")
        val dtendRegex = Regex("DTEND(?:;[^:]*)?:(.*)")
        val locationRegex = Regex("LOCATION:(.*)")
        val uidRegex = Regex("UID:(.*)")

        for (match in veventRegex.findAll(unfolded)) {
            val s = match.value
            val uid = uidRegex.find(s)?.groupValues?.get(1)?.trim() ?: UUID.randomUUID().toString()
            val summary = summaryRegex.find(s)?.groupValues?.get(1)?.trim() ?: "No Title"
            val description = descRegex.find(s)?.groupValues?.get(1)?.trim()?.replace("\\n", "\n")
            val startStr = dtstartRegex.find(s)?.groupValues?.get(1)?.trim()
            val endStr = dtendRegex.find(s)?.groupValues?.get(1)?.trim()
            val location = locationRegex.find(s)?.groupValues?.get(1)?.trim()
            events.add(CalendarEvent(uid, summary, description, formatIcsDate(startStr), formatIcsDate(endStr), location, calendarHref))
        }
        return events
    }

    private fun formatIcsDate(dateStr: String?): String? {
        if (dateStr == null) return null
        val clean = dateStr.trim()
        if (clean.length >= 8) {
            val y = clean.substring(0, 4); val m = clean.substring(4, 6); val d = clean.substring(6, 8)
            if (clean.length >= 15 && clean.contains("T")) {
                val h = clean.substring(9, 11); val min = clean.substring(11, 13)
                return "$y-$m-$d $h:$min"
            }
            return "$y-$m-$d"
        }
        return dateStr
    }

    private fun formatToIcsDate(dateTimeStr: String?): String? {
        if (dateTimeStr == null) return null
        val clean = dateTimeStr.trim()
        if (clean.length == 16 && clean[4] == '-' && clean[7] == '-' && clean[10] == ' ' && clean[13] == ':') {
            return "${clean.substring(0,4)}${clean.substring(5,7)}${clean.substring(8,10)}T${clean.substring(11,13)}${clean.substring(14,16)}00Z"
        }
        if (clean.length == 10 && clean[4] == '-' && clean[7] == '-') {
            return "${clean.substring(0,4)}${clean.substring(5,7)}${clean.substring(8,10)}"
        }
        return clean.replace("-", "").replace(":", "").replace(" ", "T")
    }

    private fun parseFiles(xml: String, requestPath: String): List<NextcloudFile> {
        val files = mutableListOf<NextcloudFile>()
        val responseRegex = Regex("<d:response[\\s\\S]*?</d:response>")
        val hrefRegex = Regex("<d:href>(.*?)</d:href>")
        val displaynameRegex = Regex("<d:displayname>(.*?)</d:displayname>")
        val contentLengthRegex = Regex("<d:getcontentlength>(\\d+)</d:getcontentlength>")
        val lastModifiedRegex = Regex("<d:getlastmodified>(.*?)</d:getlastmodified>")
        val isDirectoryRegex = Regex("<d:resourcetype[\\s\\S]*?<d:collection")
        val cleanReqPath = requestPath.trimEnd('/')

        for (resp in responseRegex.findAll(xml)) {
            val respStr = resp.value
            var href = hrefRegex.find(respStr)?.groupValues?.get(1) ?: ""
            href = java.net.URLDecoder.decode(href, "UTF-8")
            if (href.trimEnd('/') == cleanReqPath) continue
            val displayName = displaynameRegex.find(respStr)?.groupValues?.get(1)
                ?: href.split("/").lastOrNull { it.isNotEmpty() } ?: "Sans nom"
            val size = contentLengthRegex.find(respStr)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val lastModified = lastModifiedRegex.find(respStr)?.groupValues?.get(1) ?: ""
            val isDirectory = isDirectoryRegex.containsMatchIn(respStr) || href.endsWith("/")
            files.add(NextcloudFile(displayName, href, isDirectory, size, lastModified))
        }
        return files
    }
}
