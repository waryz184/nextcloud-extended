package xyz.luna.nextcloudextended.data.network

import xyz.luna.nextcloudextended.data.model.CalendarEvent
import xyz.luna.nextcloudextended.data.model.CalendarInfo
import xyz.luna.nextcloudextended.data.model.NextcloudTask
import xyz.luna.nextcloudextended.data.model.NextcloudNote
import xyz.luna.nextcloudextended.data.model.NextcloudFile
import xyz.luna.nextcloudextended.data.model.NextcloudContact
import xyz.luna.nextcloudextended.data.model.LabeledValue
import xyz.luna.nextcloudextended.data.model.PostalAddress
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit
import android.os.Handler
import android.os.Looper

class CalDavClient(
    private val serverUrl: String,
    private val username: String,
    private val password: String
) {
    private companion object {
        const val MAX_IN_MEMORY_FILE_BYTES = 25 * 1024 * 1024
    }

    init {
        require(serverUrl.trim().startsWith("https://", ignoreCase = true)) {
            "HTTPS is required"
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val credentials = Credentials.basic(username, password)
    private val activeCalls = java.util.concurrent.CopyOnWriteArrayList<okhttp3.Call>()

    val baseUrl: String get() = if (serverUrl.endsWith("/")) serverUrl.dropLast(1) else serverUrl
    fun getAuthorizationHeader(): String = credentials
    fun buildFileUrl(fileHref: String) = "$baseUrl${encodePath(fileHref)}"

    private val mainHandler = Handler(Looper.getMainLooper())
    private fun runOnMain(action: () -> Unit) { mainHandler.post(action) }

    private fun readResponseBody(response: okhttp3.Response): ByteArray {
        val body = response.body ?: throw IOException("Empty response body")
        if (body.contentLength() > MAX_IN_MEMORY_FILE_BYTES) {
            throw IOException("File exceeds the 25 MB in-app limit")
        }
        body.byteStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) return output.toByteArray()
                if (output.size() + count > MAX_IN_MEMORY_FILE_BYTES) {
                    throw IOException("File exceeds the 25 MB in-app limit")
                }
                output.write(buffer, 0, count)
            }
        }
    }

    fun cancelAll() {
        activeCalls.toList().forEach { it.cancel() }
        activeCalls.clear()
    }

    private fun okhttp3.Call.enqueueTracked(callback: okhttp3.Callback) {
        activeCalls.add(this)
        this.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                activeCalls.remove(call)
                if (!call.isCanceled()) callback.onFailure(call, e)
            }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                activeCalls.remove(call)
                response.use { callback.onResponse(call, it) }
            }
        })
    }

    // Fetches all calendars in one PROPFIND — returns event calendars (with color) + task lists
    fun getAllCalendarData(
        onSuccess: (eventCalendars: List<CalendarInfo>, taskLists: List<Pair<String, String>>) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        val url = "$baseUrl${encodePath("/remote.php/dav/calendars/$username/")}"

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
        val url = "$baseUrl${encodePath(calendarHref)}"

        // Ask the server to expand recurring events (RRULE) into concrete occurrences within
        // this window — recurrence math (BYDAY, EXDATE, leap years, ...) is delegated to the
        // CalDAV server instead of being reimplemented client-side.
        val utcFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        val now = LocalDate.now()
        val rangeStart = now.minusYears(2).atStartOfDay().format(utcFormat)
        val rangeEnd = now.plusYears(3).atStartOfDay().format(utcFormat)

        val reportBody = """<?xml version="1.0" encoding="utf-8" ?>
<c:calendar-query xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
  <d:prop>
    <c:calendar-data>
      <c:expand start="$rangeStart" end="$rangeEnd" />
    </c:calendar-data>
  </d:prop>
  <c:filter>
    <c:comp-filter name="VCALENDAR">
      <c:comp-filter name="VEVENT">
        <c:time-range start="$rangeStart" end="$rangeEnd" />
      </c:comp-filter>
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
        val url = "$baseUrl${encodePath(calendarHref)}"

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
        val fileUrl = if (task.calendarHref.endsWith("/")) "$baseUrl${encodePath("${task.calendarHref}${task.uid}.ics")}"
                      else "$baseUrl${encodePath("${task.calendarHref}/${task.uid}.ics")}"

        val icsBody = buildString {
            appendLine("BEGIN:VCALENDAR")
            appendLine("VERSION:2.0")
            appendLine("PRODID:-//Nextcloud Extended//Tasks//EN")
            appendLine("BEGIN:VTODO")
            appendLine("UID:${task.uid}")
            appendLine("SUMMARY:${escapeIcsText(task.summary)}")
            task.description?.let { appendLine("DESCRIPTION:${escapeIcsText(it)}") }
            appendLine("STATUS:${task.status}")
            icsDateLine("DUE", formatToIcsDate(task.due))?.let { appendLine(it) }
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
        val fileUrl = if (calendarHref.endsWith("/")) "$baseUrl${encodePath("$calendarHref${event.id}.ics")}"
                      else "$baseUrl${encodePath("$calendarHref/${event.id}.ics")}"

        val startIcs = formatToIcsDate(event.startTime)
        val endIcs = formatToIcsDate(event.endTime)

        val icsBody = buildString {
            appendLine("BEGIN:VCALENDAR")
            appendLine("VERSION:2.0")
            appendLine("PRODID:-//Nextcloud Extended//Calendar//EN")
            appendLine("BEGIN:VEVENT")
            appendLine("UID:${event.id}")
            appendLine("SUMMARY:${escapeIcsText(event.summary)}")
            event.description?.let { appendLine("DESCRIPTION:${escapeIcsText(it)}") }
            event.location?.let { appendLine("LOCATION:${escapeIcsText(it)}") }
            icsDateLine("DTSTART", startIcs)?.let { appendLine(it) }
            icsDateLine("DTEND", endIcs)?.let { appendLine(it) }
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
        val url = if (calendarHref.endsWith("/")) "$baseUrl${encodePath("$calendarHref$eventId.ics")}"
                  else "$baseUrl${encodePath("$calendarHref/$eventId.ics")}"
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
        val formBody = "path=${java.net.URLEncoder.encode(sharePath, "UTF-8")}&shareType=3"

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
            .url("$baseUrl${encodePath(fileHref)}")
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
                try {
                    val bytes = readResponseBody(response)
                    runOnMain { onSuccess(bytes) }
                } catch (e: Exception) {
                    runOnMain { onFailure(e) }
                }
            }
        })
    }

    fun deleteTask(task: NextcloudTask, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val fileUrl = if (task.calendarHref.endsWith("/")) "$baseUrl${encodePath("${task.calendarHref}${task.uid}.ics")}"
                      else "$baseUrl${encodePath("${task.calendarHref}/${task.uid}.ics")}"

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
            .url("$baseUrl${encodePath(cleanPath)}")
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
        val request = Request.Builder().url("$baseUrl${encodePath(fileHref)}").addHeader("Authorization", credentials).delete().build()
        client.newCall(request).enqueueTracked(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) { runOnMain { onFailure(e) } }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful || response.code == 204) runOnMain { onSuccess() }
                else runOnMain { onFailure(Exception("HTTP Error: ${response.code}")) }
            }
        })
    }

    fun createFolder(parentHref: String, folderName: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        if (!isValidDavName(folderName)) {
            runOnMain { onFailure(IllegalArgumentException("Invalid folder name")) }
            return
        }
        val cleanParent = if (parentHref.endsWith("/")) parentHref else "$parentHref/"
        val request = Request.Builder().url("$baseUrl${encodePath("$cleanParent$folderName/")}").addHeader("Authorization", credentials).method("MKCOL", null).build()
        client.newCall(request).enqueueTracked(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) { runOnMain { onFailure(e) } }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful || response.code == 201) runOnMain { onSuccess() }
                else runOnMain { onFailure(Exception("HTTP Error: ${response.code}")) }
            }
        })
    }

    fun uploadFile(parentHref: String, fileName: String, fileBytes: ByteArray, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        if (!isValidDavName(fileName)) {
            runOnMain { onFailure(IllegalArgumentException("Invalid file name")) }
            return
        }
        val cleanParent = if (parentHref.endsWith("/")) parentHref else "$parentHref/"
        val request = Request.Builder()
            .url("$baseUrl${encodePath("$cleanParent$fileName")}")
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
        val url = "$baseUrl${encodePath("/remote.php/dav/calendars/$username/$listId/")}"
        val body = """<?xml version="1.0" encoding="utf-8" ?>
<c:mkcalendar xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
  <d:set><d:prop>
    <d:displayname>${escapeXml(listName)}</d:displayname>
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
        val request = Request.Builder().url("$baseUrl${encodePath(calendarHref)}").addHeader("Authorization", credentials).delete().build()
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
  <d:displayname>${escapeXml(newName)}</d:displayname>
</d:prop></d:set></d:propertyupdate>""".trimIndent()

        val request = Request.Builder().url("$baseUrl${encodePath(calendarHref)}").addHeader("Authorization", credentials)
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
        if (!isValidDavName(newName)) {
            runOnMain { onFailure(IllegalArgumentException("Invalid file name")) }
            return
        }
        val isDir = sourceHref.endsWith("/")
        val cleanHref = if (isDir) sourceHref.dropLast(1) else sourceHref
        val parentHref = cleanHref.substring(0, cleanHref.lastIndexOf('/') + 1)
        // Hrefs are stored decoded; encodePath re-encodes both the source and the destination
        // (including any special char in newName) per segment for a valid WebDAV MOVE.
        val destHref = "$parentHref$newName" + (if (isDir) "/" else "")

        val request = Request.Builder()
            .url("$baseUrl${encodePath(sourceHref)}")
            .addHeader("Authorization", credentials)
            .addHeader("Destination", "$baseUrl${encodePath(destHref)}")
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

    // ── CardDAV (Contacts) ───────────────────────────────────────────────────────

    // Lists the user's address books (PROPFIND on the CardDAV home set).
    fun getAddressBooks(onSuccess: (List<Pair<String, String>>) -> Unit, onFailure: (Exception) -> Unit) {
        val url = "$baseUrl${encodePath("/remote.php/dav/addressbooks/users/$username/")}"
        val propfindBody = """<?xml version="1.0" encoding="utf-8" ?>
<d:propfind xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
  <d:prop>
    <d:displayname />
    <d:resourcetype />
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
            override fun onFailure(call: okhttp3.Call, e: IOException) { runOnMain { onFailure(e) } }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (!response.isSuccessful) { runOnMain { onFailure(Exception("HTTP Error: ${response.code}")) }; return }
                val body = response.body?.string() ?: ""
                try { runOnMain { onSuccess(parseAddressBooks(body)) } }
                catch (e: Exception) { runOnMain { onFailure(e) } }
            }
        })
    }

    fun getContacts(addressBookHref: String, onSuccess: (List<NextcloudContact>) -> Unit, onFailure: (Exception) -> Unit) {
        val reportBody = """<?xml version="1.0" encoding="utf-8" ?>
<card:addressbook-query xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
  <d:prop>
    <d:getetag />
    <card:address-data />
  </d:prop>
</card:addressbook-query>""".trimIndent()

        val request = Request.Builder()
            .url("$baseUrl${encodePath(addressBookHref)}")
            .addHeader("Authorization", credentials)
            .addHeader("Depth", "1")
            .addHeader("Content-Type", "application/xml; charset=utf-8")
            .method("REPORT", reportBody.toRequestBody("application/xml".toMediaType()))
            .build()

        client.newCall(request).enqueueTracked(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) { runOnMain { onFailure(e) } }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (!response.isSuccessful) { runOnMain { onFailure(Exception("HTTP Error: ${response.code}")) }; return }
                val body = response.body?.string() ?: ""
                try { runOnMain { onSuccess(parseContactsFromReport(body, addressBookHref)) } }
                catch (e: Exception) { runOnMain { onFailure(e) } }
            }
        })
    }

    fun saveContact(contact: NextcloudContact, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val fileUrl = if (contact.href.isNotEmpty()) "$baseUrl${encodePath(contact.href)}"
                      else {
                          val ab = if (contact.addressBookHref.endsWith("/")) contact.addressBookHref else "${contact.addressBookHref}/"
                          "$baseUrl${encodePath("$ab${contact.uid}.vcf")}"
                      }

        val request = Request.Builder()
            .url(fileUrl)
            .addHeader("Authorization", credentials)
            .addHeader("Content-Type", "text/vcard; charset=utf-8")
            .put(buildVcard(contact).toRequestBody("text/vcard; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).enqueueTracked(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) { runOnMain { onFailure(e) } }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful || response.code == 201 || response.code == 204) runOnMain { onSuccess() }
                else runOnMain { onFailure(Exception("HTTP Error: ${response.code}")) }
            }
        })
    }

    fun deleteContact(contact: NextcloudContact, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val request = Request.Builder().url("$baseUrl${encodePath(contact.href)}").addHeader("Authorization", credentials).delete().build()
        client.newCall(request).enqueueTracked(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) { runOnMain { onFailure(e) } }
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful || response.code == 204) runOnMain { onSuccess() }
                else runOnMain { onFailure(Exception("HTTP Error: ${response.code}")) }
            }
        })
    }

    // ── Synchronous CardDAV for the background sync adapter ──────────────────────
    // These block on the caller's thread (the SyncAdapter thread) and do not hop
    // back to the main thread.

    fun getAddressBooksSync(): List<Pair<String, String>> {
        val url = "$baseUrl${encodePath("/remote.php/dav/addressbooks/users/$username/")}"
        val propfindBody = """<?xml version="1.0" encoding="utf-8" ?>
<d:propfind xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
  <d:prop>
    <d:displayname />
    <d:resourcetype />
  </d:prop>
</d:propfind>""".trimIndent()
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", credentials)
            .addHeader("Depth", "1")
            .addHeader("Content-Type", "application/xml; charset=utf-8")
            .method("PROPFIND", propfindBody.toRequestBody("application/xml".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP Error: ${response.code}")
            return parseAddressBooks(response.body?.string() ?: "")
        }
    }

    fun getContactsSync(addressBookHref: String): List<NextcloudContact> {
        val reportBody = """<?xml version="1.0" encoding="utf-8" ?>
<card:addressbook-query xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
  <d:prop>
    <d:getetag />
    <card:address-data />
  </d:prop>
</card:addressbook-query>""".trimIndent()
        val request = Request.Builder()
            .url("$baseUrl${encodePath(addressBookHref)}")
            .addHeader("Authorization", credentials)
            .addHeader("Depth", "1")
            .addHeader("Content-Type", "application/xml; charset=utf-8")
            .method("REPORT", reportBody.toRequestBody("application/xml".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP Error: ${response.code}")
            return parseContactsFromReport(response.body?.string() ?: "", addressBookHref)
        }
    }

    /** Pushes a contact to the server. Returns the server ETag (may be empty). */
    fun saveContactSync(contact: NextcloudContact): String {
        val fileUrl = if (contact.href.isNotEmpty()) "$baseUrl${encodePath(contact.href)}"
                      else {
                          val ab = if (contact.addressBookHref.endsWith("/")) contact.addressBookHref else "${contact.addressBookHref}/"
                          "$baseUrl${encodePath("$ab${contact.uid}.vcf")}"
                      }
        val request = Request.Builder()
            .url(fileUrl)
            .addHeader("Authorization", credentials)
            .addHeader("Content-Type", "text/vcard; charset=utf-8")
            .put(buildVcard(contact).toRequestBody("text/vcard; charset=utf-8".toMediaType()))
            .build()
        val response = client.newCall(request).execute()
        response.use {
            if (!response.isSuccessful && response.code != 201 && response.code != 204) {
                throw IOException("HTTP Error: ${response.code}")
            }
            return response.headers["ETag"]?.removeSurrounding("\"").orEmpty()
        }
    }

    fun deleteContactSync(contact: NextcloudContact) {
        if (contact.href.isEmpty()) return
        val request = Request.Builder()
            .url("$baseUrl${encodePath(contact.href)}")
            .addHeader("Authorization", credentials)
            .delete()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 204) {
                throw IOException("HTTP Error: ${response.code}")
            }
        }
    }

    // Rebuilds a vCard 3.0 from the managed fields while carrying over any property we don't model
    // (NICKNAME, URL, IMPP, X-*…) from the original card so an edit doesn't drop it.
    private fun buildVcard(contact: NextcloudContact): String {
        val managed = setOf("BEGIN", "END", "VERSION", "UID", "FN", "N", "TEL", "EMAIL", "ORG", "REV",
                            "ADR", "BDAY", "PHOTO", "CATEGORIES")
        val preserved = contact.rawVcard
            ?.lineSequence()
            ?.filter { line ->
                val prop = line.substringBefore(':').substringBefore(';').trim().uppercase()
                prop.isNotEmpty() && prop !in managed
            }
            ?.map { it.trimEnd('\r') }
            ?.toList().orEmpty()

        val name = contact.fullName.trim()
        val tokens = name.split(" ").filter { it.isNotBlank() }
        val family = if (tokens.size > 1) tokens.last() else ""
        val given = if (tokens.size > 1) tokens.dropLast(1).joinToString(" ") else name

        fun typeParam(type: String) = if (type.isNotBlank()) ";TYPE=$type" else ""

        return buildString {
            appendLine("BEGIN:VCARD")
            appendLine("VERSION:3.0")
            appendLine("UID:${contact.uid}")
            appendLine("FN:${escapeIcsText(name)}")
            appendLine("N:${escapeIcsText(family)};${escapeIcsText(given)};;;")
            contact.phones.filter { it.value.isNotBlank() }.forEach {
                appendLine("TEL${typeParam(it.type)}:${escapeIcsText(it.value.trim())}")
            }
            contact.emails.filter { it.value.isNotBlank() }.forEach {
                appendLine("EMAIL${typeParam(it.type)}:${escapeIcsText(it.value.trim())}")
            }
            contact.organization?.takeIf { it.isNotBlank() }?.let { appendLine("ORG:${escapeIcsText(it.trim())}") }
            contact.addresses.filter { !it.isEmpty }.forEach { a ->
                // ADR components: po-box ; ext ; street ; locality ; region ; postal-code ; country
                appendLine("ADR${typeParam(a.type)}:;;${escapeIcsText(a.street.trim())};${escapeIcsText(a.city.trim())};;${escapeIcsText(a.postalCode.trim())};${escapeIcsText(a.country.trim())}")
            }
            contact.birthday?.takeIf { it.isNotBlank() }?.let { appendLine("BDAY:$it") }
            contact.categories.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { cats ->
                appendLine("CATEGORIES:${cats.joinToString(",") { escapeIcsText(it.trim()) }}")
            }
            contact.photoBase64?.takeIf { it.isNotBlank() }?.let { photo ->
                val type = contact.photoMimeType?.substringAfter("/")?.uppercase()?.takeIf { it.isNotBlank() } ?: "JPEG"
                appendLine("PHOTO;ENCODING=b;TYPE=$type:$photo")
            }
            preserved.forEach { appendLine(it) }
            append("END:VCARD")
        }
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
            val hrefRaw = hrefRegex.find(respStr)?.groupValues?.get(1) ?: ""
            if (hrefRaw.isEmpty() || !calendarResourceTypeRegex.containsMatchIn(respStr)) continue
            // Store the href decoded so it lives in the same space as file/contact hrefs;
            // encodePath() re-encodes it per segment when a request URL is built.
            val href = runCatching { java.net.URLDecoder.decode(hrefRaw, "UTF-8") }.getOrDefault(hrefRaw)
            val supportedComps = compRegex.findAll(respStr).map { it.groupValues[1] }.toSet()
            if (filterComponent !in supportedComps) continue
            val displayname = displaynameRegex.find(respStr)?.groupValues?.get(1)?.let { unescapeXml(it) } ?: "Calendar"
            val colorHex = colorRegex.find(respStr)?.groupValues?.get(1)?.let { "#${it.take(6)}" } ?: ""
            result.add(CalendarInfo(href, displayname, colorHex))
        }
        return result
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

    private fun parseAddressBooks(xml: String): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val responseRegex = Regex("<d:response[\\s\\S]*?</d:response>")
        val hrefRegex = Regex("<d:href>(.*?)</d:href>")
        val displaynameRegex = Regex("<d:displayname>(.*?)</d:displayname>")
        val addressbookTypeRegex = Regex("<[a-zA-Z0-9:]*addressbook[\\s/>]")
        for (resp in responseRegex.findAll(xml)) {
            val respStr = resp.value
            val hrefRaw = hrefRegex.find(respStr)?.groupValues?.get(1) ?: ""
            if (hrefRaw.isEmpty() || !addressbookTypeRegex.containsMatchIn(respStr)) continue
            val href = runCatching { java.net.URLDecoder.decode(hrefRaw, "UTF-8") }.getOrDefault(hrefRaw)
            val displayname = displaynameRegex.find(respStr)?.groupValues?.get(1)?.let { unescapeXml(it) } ?: "Contacts"
            result.add(Pair(href, displayname))
        }
        return result
    }

    private fun parseContactsFromReport(xml: String, addressBookHref: String): List<NextcloudContact> {
        val contacts = mutableListOf<NextcloudContact>()
        val responseRegex = Regex("<d:response[\\s\\S]*?</d:response>")
        val hrefRegex = Regex("<d:href>(.*?)</d:href>")
        val etagRegex = Regex("<d:getetag>(.*?)</d:getetag>")
        val dataRegex = Regex("<[a-zA-Z0-9:]*address-data[^>]*>([\\s\\S]*?)</[a-zA-Z0-9:]*address-data>")
        for (resp in responseRegex.findAll(xml)) {
            val respStr = resp.value
            val vcardXml = dataRegex.find(respStr)?.groupValues?.get(1) ?: continue
            val vcard = unescapeXml(vcardXml).trim()
            if (vcard.isEmpty()) continue
            val href = hrefRegex.find(respStr)?.groupValues?.get(1)?.let {
                runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
            } ?: ""
            val etag = etagRegex.find(respStr)?.groupValues?.get(1)?.trim()?.removeSurrounding("\"") ?: ""
            contacts.add(parseVcard(vcard, addressBookHref, href, etag))
        }
        return contacts
    }

    private fun parseVcard(vcard: String, addressBookHref: String, href: String, etag: String = ""): NextcloudContact {
        val unfolded = vcard.replace("\r\n ", "").replace("\r\n\t", "").replace("\n ", "").replace("\n\t", "")
        fun rawFirst(prop: String) = Regex("(?m)^$prop(?:;[^:\\r\\n]*)?:(.*)$").find(unfolded)?.groupValues?.get(1)?.trimEnd('\r')

        val uid = rawFirst("UID")?.trim()?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val fn = rawFirst("FN")?.let { unescapeIcsText(it).trim() }
        val nameFromN = rawFirst("N")?.let { n ->
            val parts = splitVcardComponents(n, ';')
            listOf(parts.getOrNull(1) ?: "", parts.getOrNull(0) ?: "").filter { it.isNotBlank() }.joinToString(" ")
        }
        val fullName = fn?.takeIf { it.isNotBlank() } ?: nameFromN?.takeIf { it.isNotBlank() } ?: "?"

        val phones = vcardEntries(unfolded, "TEL")
            .map { (params, value) -> LabeledValue(unescapeIcsText(value).trim(), extractType(params)) }
            .filter { it.value.isNotBlank() }
        val emails = vcardEntries(unfolded, "EMAIL")
            .map { (params, value) -> LabeledValue(unescapeIcsText(value).trim(), extractType(params)) }
            .filter { it.value.isNotBlank() }

        val org = rawFirst("ORG")?.let { splitVcardComponents(it, ';').filter { c -> c.isNotBlank() }.joinToString(" · ") }
            ?.takeIf { it.isNotBlank() }

        val addresses = vcardEntries(unfolded, "ADR").mapNotNull { (params, value) ->
            val c = splitVcardComponents(value, ';')
            PostalAddress(
                type = extractType(params),
                street = c.getOrNull(2) ?: "",
                city = c.getOrNull(3) ?: "",
                postalCode = c.getOrNull(5) ?: "",
                country = c.getOrNull(6) ?: ""
            ).takeUnless { it.isEmpty }
        }

        val birthday = rawFirst("BDAY")?.let { normalizeBday(it.trim()) }
        val categories = rawFirst("CATEGORIES")?.let { splitVcardComponents(it, ',') }?.filter { it.isNotBlank() }.orEmpty()
        val (photo, photoMime) = parsePhoto(unfolded)

        return NextcloudContact(uid, fullName, phones, emails, org, addresses, birthday, photo, photoMime, categories, addressBookHref, href, etag, unfolded)
    }

    // Returns (paramsString, rawValue) for every line of the given vCard property.
    private fun vcardEntries(unfolded: String, prop: String): List<Pair<String, String>> =
        Regex("(?m)^$prop((?:;[^:\\r\\n]*)?):(.*)$").findAll(unfolded)
            .map { Pair(it.groupValues[1], it.groupValues[2].trimEnd('\r')) }
            .toList()

    // Extracts (base64, mimeType) from the PHOTO property — handles vCard 3.0 inline and 4.0 data URI.
    private fun parsePhoto(unfolded: String): Pair<String?, String?> {
        val (params, value) = vcardEntries(unfolded, "PHOTO").firstOrNull() ?: return null to null
        val v = value.trim()
        if (v.startsWith("data:", ignoreCase = true)) {
            val mime = Regex("data:([^;]+)", RegexOption.IGNORE_CASE).find(v)?.groupValues?.get(1)
            val b64 = v.substringAfter("base64,", "").ifBlank { return null to null }
            return b64 to mime
        }
        val isBase64 = params.contains("ENCODING=b", true) || params.contains("BASE64", true)
        if (!isBase64 || v.isBlank()) return null to null
        val typeTok = Regex("TYPE=([^;:]*)", RegexOption.IGNORE_CASE).find(params)?.groupValues?.get(1)?.trim()
        val mime = typeTok?.takeIf { it.isNotBlank() }?.let { "image/${it.lowercase()}" }
        return v to mime
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
            val summary = summaryRegex.find(s)?.groupValues?.get(1)?.let { unescapeIcsText(it).trim() } ?: "Untitled task"
            val description = descRegex.find(s)?.groupValues?.get(1)?.let { unescapeIcsText(it).trim() }
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
        val recurrenceIdRegex = Regex("RECURRENCE-ID(?:;[^:]*)?:")
        val rruleRegex = Regex("RRULE:")

        for (match in veventRegex.findAll(unfolded)) {
            val s = match.value
            val uid = uidRegex.find(s)?.groupValues?.get(1)?.trim() ?: UUID.randomUUID().toString()
            val summary = summaryRegex.find(s)?.groupValues?.get(1)?.let { unescapeIcsText(it).trim() } ?: "No Title"
            val description = descRegex.find(s)?.groupValues?.get(1)?.let { unescapeIcsText(it).trim() }
            val startStr = dtstartRegex.find(s)?.groupValues?.get(1)?.trim()
            val endStr = dtendRegex.find(s)?.groupValues?.get(1)?.trim()
            val location = locationRegex.find(s)?.groupValues?.get(1)?.let { unescapeIcsText(it).trim() }
            // A recurring event expanded server-side into several occurrences carries either
            // RECURRENCE-ID (a computed instance) or RRULE (the master, for servers that don't expand).
            val isRecurringInstance = recurrenceIdRegex.containsMatchIn(s) || rruleRegex.containsMatchIn(s)
            events.add(CalendarEvent(uid, summary, description, formatIcsDate(startStr), formatIcsDate(endStr), location, calendarHref, isRecurringInstance))
        }
        return events
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
            val displayName = displaynameRegex.find(respStr)?.groupValues?.get(1)?.let { unescapeXml(it) }
                ?: href.split("/").lastOrNull { it.isNotEmpty() } ?: "Sans nom"
            val size = contentLengthRegex.find(respStr)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val lastModified = lastModifiedRegex.find(respStr)?.groupValues?.get(1) ?: ""
            val isDirectory = isDirectoryRegex.containsMatchIn(respStr) || href.endsWith("/")
            files.add(NextcloudFile(displayName, href, isDirectory, size, lastModified))
        }
        return files
    }
}
