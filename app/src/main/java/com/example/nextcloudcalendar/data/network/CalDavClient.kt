package com.example.nextcloudcalendar.data.network

import com.example.nextcloudcalendar.data.model.CalendarEvent
import com.example.nextcloudcalendar.data.model.NextcloudTask
import com.example.nextcloudcalendar.data.model.NextcloudNote
import com.example.nextcloudcalendar.data.model.NextcloudFile
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import android.os.Handler
import android.os.Looper
import android.text.Html

class CalDavClient(
    private val baseUrl: String,
    private val username: String,
    private val password: String
) {
    private val client = OkHttpClient()
    private val credentials = Credentials.basic(username, password)

    private val mainHandler = Handler(Looper.getMainLooper())
    private fun runOnMain(action: () -> Unit) {
        mainHandler.post(action)
    }

    // Fetch the list of calendar path URLs for user
    fun getCalendars(onSuccess: (List<Pair<String, String>>) -> Unit, onFailure: (Exception) -> Unit) {
        val url = if (baseUrl.endsWith("/")) "${baseUrl}remote.php/dav/calendars/$username/" 
                  else "$baseUrl/remote.php/dav/calendars/$username/"
                  
        val propfindBody = """<?xml version="1.0" encoding="utf-8" ?>
        <d:propfind xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
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

        client.newCall(request).enqueue(object : okhttp3.Callback {
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
                    val calendars = parseCalendars(body)
                    runOnMain { onSuccess(calendars) }
                } catch (e: Exception) {
                    runOnMain { onFailure(e) }
                }
            }
        })
    }

    // Fetch all VEVENTs in a calendar
    fun getEvents(calendarHref: String, onSuccess: (List<CalendarEvent>) -> Unit, onFailure: (Exception) -> Unit) {
        val rootUrl = if (baseUrl.endsWith("/")) baseUrl.substring(0, baseUrl.length - 1) else baseUrl
        val url = "$rootUrl$calendarHref"

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

        client.newCall(request).enqueue(object : okhttp3.Callback {
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
                    val events = parseEventsFromReport(body)
                    runOnMain { onSuccess(events) }
                } catch (e: Exception) {
                    runOnMain { onFailure(e) }
                }
            }
        })
    }

    // Fetch all VTODOs in a calendar collection
    fun getTasks(calendarHref: String, onSuccess: (List<NextcloudTask>) -> Unit, onFailure: (Exception) -> Unit) {
        val rootUrl = if (baseUrl.endsWith("/")) baseUrl.substring(0, baseUrl.length - 1) else baseUrl
        val url = "$rootUrl$calendarHref"

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

        client.newCall(request).enqueue(object : okhttp3.Callback {
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

    // Save (Create or Update) a NextcloudTask via PUT
    fun saveTask(task: NextcloudTask, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val rootUrl = if (baseUrl.endsWith("/")) baseUrl.substring(0, baseUrl.length - 1) else baseUrl
        val fileUrl = if (task.calendarHref.endsWith("/")) "$rootUrl${task.calendarHref}${task.uid}.ics"
                      else "$rootUrl${task.calendarHref}/${task.uid}.ics"

        val cleanSummary = task.summary.replace("\n", " ").replace("\r", "")
        val cleanDescription = task.description?.replace("\n", "\\n")?.replace("\r", "")

        val icsBody = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Hermes Agent//Nextcloud Tasks//EN
            BEGIN:VTODO
            UID:${task.uid}
            SUMMARY:$cleanSummary
            ${if (cleanDescription != null) "DESCRIPTION:$cleanDescription" else ""}
            STATUS:${task.status}
            ${if (task.due != null) "DUE:${task.due.replace("-", "").replace(" ", "T").replace(":", "")}Z" else ""}
            END:VTODO
            END:VCALENDAR
        """.trimIndent()

        val request = Request.Builder()
            .url(fileUrl)
            .addHeader("Authorization", credentials)
            .addHeader("Content-Type", "text/calendar; charset=utf-8")
            .put(icsBody.trim().toRequestBody("text/calendar; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                runOnMain { onFailure(e) }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful || response.code == 201 || response.code == 204) {
                    runOnMain { onSuccess() }
                } else {
                    runOnMain { onFailure(Exception("HTTP Error: ${response.code}")) }
                }
            }
        })
    }

    // Save (Create or Update) a CalendarEvent via PUT
    fun saveEvent(calendarHref: String, event: CalendarEvent, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val rootUrl = if (baseUrl.endsWith("/")) baseUrl.substring(0, baseUrl.length - 1) else baseUrl
        val fileUrl = if (calendarHref.endsWith("/")) "$rootUrl$calendarHref${event.id}.ics"
                      else "$rootUrl$calendarHref/${event.id}.ics"

        val cleanSummary = event.summary.replace("\n", " ").replace("\r", "")
        val cleanDescription = event.description?.replace("\n", "\\n")?.replace("\r", "")
        val cleanLocation = event.location?.replace("\n", " ")?.replace("\r", "")

        val startIcs = formatToIcsDate(event.startTime)
        val endIcs = formatToIcsDate(event.endTime)

        val icsBody = """
            BEGIN:VCALENDAR
            VERSION:2.0
            PRODID:-//Hermes Agent//Nextcloud Calendar//EN
            BEGIN:VEVENT
            UID:${event.id}
            SUMMARY:$cleanSummary
            ${if (cleanDescription != null) "DESCRIPTION:$cleanDescription" else ""}
            ${if (cleanLocation != null) "LOCATION:$cleanLocation" else ""}
            ${if (startIcs != null) "DTSTART:$startIcs" else ""}
            ${if (endIcs != null) "DTEND:$endIcs" else ""}
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()

        val request = Request.Builder()
            .url(fileUrl)
            .addHeader("Authorization", credentials)
            .addHeader("Content-Type", "text/calendar; charset=utf-8")
            .put(icsBody.trim().toRequestBody("text/calendar; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                runOnMain { onFailure(e) }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful || response.code == 201 || response.code == 204) {
                    runOnMain { onSuccess() }
                } else {
                    runOnMain { onFailure(Exception("HTTP Error: ${response.code}")) }
                }
            }
        })
    }

    private fun formatToIcsDate(dateTimeStr: String?): String? {
        if (dateTimeStr == null) return null
        val clean = dateTimeStr.trim()
        if (clean.length == 16 && clean[4] == '-' && clean[7] == '-' && clean[10] == ' ' && clean[13] == ':') {
            val year = clean.substring(0, 4)
            val month = clean.substring(5, 7)
            val day = clean.substring(8, 10)
            val hour = clean.substring(11, 13)
            val minute = clean.substring(14, 16)
            return "${year}${month}${day}T${hour}${minute}00Z"
        }
        if (clean.length == 10 && clean[4] == '-' && clean[7] == '-') {
            val year = clean.substring(0, 4)
            val month = clean.substring(5, 7)
            val day = clean.substring(8, 10)
            return "${year}${month}${day}"
        }
        return clean.replace("-", "").replace(":", "").replace(" ", "T")
    }

    // Delete a NextcloudTask
    fun deleteTask(task: NextcloudTask, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val rootUrl = if (baseUrl.endsWith("/")) baseUrl.substring(0, baseUrl.length - 1) else baseUrl
        val fileUrl = if (task.calendarHref.endsWith("/")) "$rootUrl${task.calendarHref}${task.uid}.ics"
                      else "$rootUrl${task.calendarHref}/${task.uid}.ics"

        val request = Request.Builder()
            .url(fileUrl)
            .addHeader("Authorization", credentials)
            .delete()
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                runOnMain { onFailure(e) }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful || response.code == 204) {
                    runOnMain { onSuccess() }
                } else {
                    runOnMain { onFailure(Exception("HTTP Error: ${response.code}")) }
                }
            }
        })
    }

    // Nextcloud Notes: Fetch all notes
    fun getNotes(onSuccess: (List<NextcloudNote>) -> Unit, onFailure: (Exception) -> Unit) {
        val rootUrl = if (baseUrl.endsWith("/")) baseUrl.substring(0, baseUrl.length - 1) else baseUrl
        val url = "$rootUrl/index.php/apps/notes/api/v1/notes"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", credentials)
            .addHeader("OCS-APIRequest", "true")
            .addHeader("Accept", "application/json")
            .get()
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
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
                    val notes = mutableListOf<NextcloudNote>()
                    val jsonArray = org.json.JSONArray(body)
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.getJSONObject(i)
                        notes.add(
                            NextcloudNote(
                                id = obj.getInt("id"),
                                title = obj.getString("title"),
                                content = obj.optString("content", ""),
                                category = obj.optString("category", ""),
                                modified = obj.optLong("modified", 0L),
                                favorite = obj.optBoolean("favorite", false)
                            )
                        )
                    }
                    runOnMain { onSuccess(notes) }
                } catch (e: Exception) {
                    runOnMain { onFailure(e) }
                }
            }
        })
    }

    // Nextcloud Notes: Create a note
    fun createNote(title: String, content: String, category: String, onSuccess: (NextcloudNote) -> Unit, onFailure: (Exception) -> Unit) {
        val rootUrl = if (baseUrl.endsWith("/")) baseUrl.substring(0, baseUrl.length - 1) else baseUrl
        val url = "$rootUrl/index.php/apps/notes/api/v1/notes"

        val jsonObj = org.json.JSONObject()
        jsonObj.put("title", title)
        jsonObj.put("content", content)
        jsonObj.put("category", category)

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", credentials)
            .addHeader("OCS-APIRequest", "true")
            .addHeader("Accept", "application/json")
            .post(jsonObj.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
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
                    val obj = org.json.JSONObject(body)
                    val note = NextcloudNote(
                        id = obj.getInt("id"),
                        title = obj.getString("title"),
                        content = obj.optString("content", ""),
                        category = obj.optString("category", ""),
                        modified = obj.optLong("modified", 0L),
                        favorite = obj.optBoolean("favorite", false)
                    )
                    runOnMain { onSuccess(note) }
                } catch (e: Exception) {
                    runOnMain { onFailure(e) }
                }
            }
        })
    }

    // Nextcloud Notes: Update a note
    fun updateNote(noteId: Int, title: String, content: String, category: String, favorite: Boolean, onSuccess: (NextcloudNote) -> Unit, onFailure: (Exception) -> Unit) {
        val rootUrl = if (baseUrl.endsWith("/")) baseUrl.substring(0, baseUrl.length - 1) else baseUrl
        val url = "$rootUrl/index.php/apps/notes/api/v1/notes/$noteId"

        val jsonObj = org.json.JSONObject()
        jsonObj.put("title", title)
        jsonObj.put("content", content)
        jsonObj.put("category", category)
        jsonObj.put("favorite", favorite)

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", credentials)
            .addHeader("OCS-APIRequest", "true")
            .addHeader("Accept", "application/json")
            .put(jsonObj.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
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
                    val obj = org.json.JSONObject(body)
                    val note = NextcloudNote(
                        id = obj.getInt("id"),
                        title = obj.getString("title"),
                        content = obj.optString("content", ""),
                        category = obj.optString("category", ""),
                        modified = obj.optLong("modified", 0L),
                        favorite = obj.optBoolean("favorite", false)
                    )
                    runOnMain { onSuccess(note) }
                } catch (e: Exception) {
                    runOnMain { onFailure(e) }
                }
            }
        })
    }

    // Nextcloud Notes: Delete a note
    fun deleteNote(noteId: Int, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val rootUrl = if (baseUrl.endsWith("/")) baseUrl.substring(0, baseUrl.length - 1) else baseUrl
        val url = "$rootUrl/index.php/apps/notes/api/v1/notes/$noteId"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", credentials)
            .addHeader("OCS-APIRequest", "true")
            .addHeader("Accept", "application/json")
            .delete()
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                runOnMain { onFailure(e) }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful || response.code == 204) {
                    runOnMain { onSuccess() }
                } else {
                    runOnMain { onFailure(Exception("HTTP Error: ${response.code}")) }
                }
            }
        })
    }

    // Parse calendars PROPFIND XML response
    private fun parseCalendars(xml: String): List<Pair<String, String>> {
        val calendars = mutableListOf<Pair<String, String>>()
        val responseRegex = Regex("<d:response[\\s\\S]*?</d:response>")
        val hrefRegex = Regex("<d:href>(.*?)</d:href>")
        val displaynameRegex = Regex("<d:displayname>(.*?)</d:displayname>")
        val calendarResourceTypeRegex = Regex("<[a-zA-Z0-9:]*calendar")

        val responses = responseRegex.findAll(xml)
        for (resp in responses) {
            val respStr = resp.value
            val href = hrefRegex.find(respStr)?.groupValues?.get(1) ?: ""
            val displayname = displaynameRegex.find(respStr)?.groupValues?.get(1) ?: "Calendar"
            
            // Only add if it is a calendar resource type
            if (calendarResourceTypeRegex.containsMatchIn(respStr) && href.isNotEmpty()) {
                calendars.add(Pair(href, displayname))
            }
        }
        return calendars
    }

    // Parse events from REPORT XML response containing multiple iCalendar <c:calendar-data> tags
    private fun parseEventsFromReport(xml: String): List<CalendarEvent> {
        val events = mutableListOf<CalendarEvent>()
        val calDataRegex = Regex("<[a-zA-Z0-9:]*calendar-data>([\\s\\S]*?)</[a-zA-Z0-9:]*calendar-data>")
        
        val matches = calDataRegex.findAll(xml)
        for (m in matches) {
            val ics = m.groupValues[1]
                .let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString() }
            events.addAll(parseIcs(ics))
        }
        return events
    }

    // Parse tasks from REPORT XML response containing multiple iCalendar tags
    private fun parseTasksFromReport(xml: String, calendarHref: String): List<NextcloudTask> {
        val tasks = mutableListOf<NextcloudTask>()
        val calDataRegex = Regex("<[a-zA-Z0-9:]*calendar-data>([\\s\\S]*?)</[a-zA-Z0-9:]*calendar-data>")
        
        val matches = calDataRegex.findAll(xml)
        for (m in matches) {
            val ics = m.groupValues[1]
                .let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString() }
            tasks.addAll(parseIcsTasks(ics, calendarHref))
        }
        return tasks
    }

    private fun parseIcsTasks(icsContent: String, calendarHref: String): List<NextcloudTask> {
        val tasks = mutableListOf<NextcloudTask>()
        val vtodoRegex = Regex("BEGIN:VTODO[\\s\\S]*?END:VTODO")
        
        val summaryRegex = Regex("SUMMARY:(.*)")
        val descRegex = Regex("DESCRIPTION:(.*)")
        val statusRegex = Regex("STATUS:(.*)")
        val dueRegex = Regex("DUE(?:;[^:]*)?:(.*)")
        val uidRegex = Regex("UID:(.*)")

        val matches = vtodoRegex.findAll(icsContent)
        for (match in matches) {
            val todoStr = match.value
            val uid = uidRegex.find(todoStr)?.groupValues?.get(1)?.trim() ?: UUID.randomUUID().toString()
            val summary = summaryRegex.find(todoStr)?.groupValues?.get(1)?.trim() ?: "Tâche sans titre"
            val description = descRegex.find(todoStr)?.groupValues?.get(1)?.trim()?.replace("\\n", "\n")
            val status = statusRegex.find(todoStr)?.groupValues?.get(1)?.trim() ?: "NEEDS-ACTION"
            val due = dueRegex.find(todoStr)?.groupValues?.get(1)?.trim()

            tasks.add(NextcloudTask(uid, summary, description, status, formatIcsDate(due), calendarHref))
        }
        return tasks
    }

    // Parse standard iCalendar text
    private fun parseIcs(icsContent: String): List<CalendarEvent> {
        val events = mutableListOf<CalendarEvent>()
        val veventRegex = Regex("BEGIN:VEVENT[\\s\\S]*?END:VEVENT")
        
        val summaryRegex = Regex("SUMMARY:(.*)")
        val descRegex = Regex("DESCRIPTION:(.*)")
        val dtstartRegex = Regex("DTSTART(?:;[^:]*)?:(.*)")
        val dtendRegex = Regex("DTEND(?:;[^:]*)?:(.*)")
        val locationRegex = Regex("LOCATION:(.*)")
        val uidRegex = Regex("UID:(.*)")

        val matches = veventRegex.findAll(icsContent)
        for (match in matches) {
            val eventStr = match.value
            val uid = uidRegex.find(eventStr)?.groupValues?.get(1)?.trim() ?: UUID.randomUUID().toString()
            val summary = summaryRegex.find(eventStr)?.groupValues?.get(1)?.trim() ?: "No Title"
            val description = descRegex.find(eventStr)?.groupValues?.get(1)?.trim()?.replace("\\n", "\n")
            val startStr = dtstartRegex.find(eventStr)?.groupValues?.get(1)?.trim()
            val endStr = dtendRegex.find(eventStr)?.groupValues?.get(1)?.trim()
            val location = locationRegex.find(eventStr)?.groupValues?.get(1)?.trim()

            events.add(CalendarEvent(uid, summary, description, formatIcsDate(startStr), formatIcsDate(endStr), location))
        }
        return events
    }

    // Simple parser for 20260528T150000Z to readable 2026-05-28 15:00
    private fun formatIcsDate(dateStr: String?): String? {
        if (dateStr == null) return null
        val clean = dateStr.trim()
        if (clean.length >= 8) {
            val year = clean.substring(0, 4)
            val month = clean.substring(4, 6)
            val day = clean.substring(6, 8)
            if (clean.length >= 15 && clean.contains("T")) {
                val hour = clean.substring(9, 11)
                val minute = clean.substring(11, 13)
                return "$year-$month-$day $hour:$minute"
            }
            return "$year-$month-$day"
        }
        return dateStr
    }

    // Nextcloud Files: Get list of files in a directory
    fun getFiles(folderPath: String, onSuccess: (List<NextcloudFile>) -> Unit, onFailure: (Exception) -> Unit) {
        val rootUrl = if (baseUrl.endsWith("/")) baseUrl.substring(0, baseUrl.length - 1) else baseUrl
        val cleanPath = if (folderPath.startsWith("/")) folderPath else "/$folderPath"
        val url = "$rootUrl$cleanPath"

        val propfindBody = """<?xml version="1.0" encoding="utf-8" ?>
        <d:propfind xmlns:d="DAV:">
          <d:prop>
            <d:displayname />
            <d:getcontentlength />
            <d:getlastmodified />
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

        client.newCall(request).enqueue(object : okhttp3.Callback {
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
                    val files = parseFiles(body, cleanPath)
                    runOnMain { onSuccess(files) }
                } catch (e: Exception) {
                    runOnMain { onFailure(e) }
                }
            }
        })
    }

    private fun parseFiles(xml: String, requestPath: String): List<NextcloudFile> {
        val files = mutableListOf<NextcloudFile>()
        val responseRegex = Regex("<d:response[\\s\\S]*?</d:response>")
        val hrefRegex = Regex("<d:href>(.*?)</d:href>")
        val displaynameRegex = Regex("<d:displayname>(.*?)</d:displayname>")
        val contentLengthRegex = Regex("<d:getcontentlength>(\\d+)</d:getcontentlength>")
        val lastModifiedRegex = Regex("<d:getlastmodified>(.*?)</d:getlastmodified>")
        val isDirectoryRegex = Regex("<d:resourcetype[\\s\\S]*?<d:collection")

        val responses = responseRegex.findAll(xml)
        for (resp in responses) {
            val respStr = resp.value
            var href = hrefRegex.find(respStr)?.groupValues?.get(1) ?: ""
            href = java.net.URLDecoder.decode(href, "UTF-8")
            
            val cleanHref = if (href.endsWith("/")) href.substring(0, href.length - 1) else href
            val cleanReqPath = if (requestPath.endsWith("/")) requestPath.substring(0, requestPath.length - 1) else requestPath
            if (cleanHref.equals(cleanReqPath, ignoreCase = true)) {
                continue
            }

            val displayName = displaynameRegex.find(respStr)?.groupValues?.get(1) ?: run {
                val parts = href.split("/")
                if (parts.isNotEmpty()) {
                    val lastPart = parts.last()
                    if (lastPart.isEmpty() && parts.size > 1) parts[parts.size - 2] else lastPart
                } else {
                    "Sans nom"
                }
            }

            val size = contentLengthRegex.find(respStr)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val lastModified = lastModifiedRegex.find(respStr)?.groupValues?.get(1) ?: ""
            val isDirectory = isDirectoryRegex.containsMatchIn(respStr) || href.endsWith("/")

            files.add(NextcloudFile(displayName, href, isDirectory, size, lastModified))
        }
        return files
    }

    // Delete a file or folder
    fun deleteFile(fileHref: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val rootUrl = if (baseUrl.endsWith("/")) baseUrl.substring(0, baseUrl.length - 1) else baseUrl
        val url = "$rootUrl$fileHref"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", credentials)
            .delete()
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                runOnMain { onFailure(e) }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful || response.code == 204) {
                    runOnMain { onSuccess() }
                } else {
                    runOnMain { onFailure(Exception("HTTP Error: ${response.code}")) }
                }
            }
        })
    }

    // Create a new folder (MKCOL)
    fun createFolder(parentHref: String, folderName: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val rootUrl = if (baseUrl.endsWith("/")) baseUrl.substring(0, baseUrl.length - 1) else baseUrl
        val cleanParent = if (parentHref.endsWith("/")) parentHref else "$parentHref/"
        val url = "$rootUrl$cleanParent$folderName/"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", credentials)
            .method("MKCOL", null)
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                runOnMain { onFailure(e) }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful || response.code == 201) {
                    runOnMain { onSuccess() }
                } else {
                    runOnMain { onFailure(Exception("HTTP Error: ${response.code}")) }
                }
            }
        })
    }

    // Upload a file (PUT)
    fun uploadFile(parentHref: String, fileName: String, fileBytes: ByteArray, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val rootUrl = if (baseUrl.endsWith("/")) baseUrl.substring(0, baseUrl.length - 1) else baseUrl
        val cleanParent = if (parentHref.endsWith("/")) parentHref else "$parentHref/"
        val url = "$rootUrl$cleanParent$fileName"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", credentials)
            .put(fileBytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                runOnMain { onFailure(e) }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful || response.code == 201 || response.code == 204) {
                    runOnMain { onSuccess() }
                } else {
                    runOnMain { onFailure(Exception("HTTP Error: ${response.code}")) }
                }
            }
        })
    }

    // Create a new Task List (MKCALENDAR)
    fun createTaskList(listName: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val rootUrl = if (baseUrl.endsWith("/")) baseUrl.substring(0, baseUrl.length - 1) else baseUrl
        val listId = UUID.randomUUID().toString()
        val url = "$rootUrl/remote.php/dav/calendars/$username/$listId/"

        val mkcalendarBody = """<?xml version="1.0" encoding="utf-8" ?>
        <c:mkcalendar xmlns:d="DAV:" xmlns:c="urn:ietf:params:xml:ns:caldav">
          <d:set>
            <d:prop>
              <d:displayname>$listName</d:displayname>
              <c:supported-calendar-component-set>
                <c:comp name="VTODO" />
              </c:supported-calendar-component-set>
            </d:prop>
          </d:set>
        </c:mkcalendar>""".trimIndent()

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", credentials)
            .addHeader("Content-Type", "application/xml; charset=utf-8")
            .method("MKCALENDAR", mkcalendarBody.toRequestBody("application/xml".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                runOnMain { onFailure(e) }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful || response.code == 201) {
                    runOnMain { onSuccess() }
                } else {
                    runOnMain { onFailure(Exception("HTTP Error: ${response.code}")) }
                }
            }
        })
    }

    // Delete a Task List (DELETE)
    fun deleteTaskList(calendarHref: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val rootUrl = if (baseUrl.endsWith("/")) baseUrl.substring(0, baseUrl.length - 1) else baseUrl
        val url = "$rootUrl$calendarHref"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", credentials)
            .delete()
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                runOnMain { onFailure(e) }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful || response.code == 204) {
                    runOnMain { onSuccess() }
                } else {
                    runOnMain { onFailure(Exception("HTTP Error: ${response.code}")) }
                }
            }
        })
    }

    // Rename a Task List (PROPPATCH)
    fun renameTaskList(calendarHref: String, newName: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val rootUrl = if (baseUrl.endsWith("/")) baseUrl.substring(0, baseUrl.length - 1) else baseUrl
        val url = "$rootUrl$calendarHref"

        val proppatchBody = """<?xml version="1.0" encoding="utf-8" ?>
        <d:propertyupdate xmlns:d="DAV:">
          <d:set>
            <d:prop>
              <d:displayname>$newName</d:displayname>
            </d:prop>
          </d:set>
        </d:propertyupdate>""".trimIndent()

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", credentials)
            .addHeader("Content-Type", "application/xml; charset=utf-8")
            .method("PROPPATCH", proppatchBody.toRequestBody("application/xml".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                runOnMain { onFailure(e) }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful || response.code == 200 || response.code == 207) {
                    runOnMain { onSuccess() }
                } else {
                    runOnMain { onFailure(Exception("HTTP Error: ${response.code}")) }
                }
            }
        })
    }

    // Rename/Move a file or folder (MOVE)
    fun renameFile(sourceHref: String, newName: String, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        val rootUrl = if (baseUrl.endsWith("/")) baseUrl.substring(0, baseUrl.length - 1) else baseUrl
        val sourceUrl = "$rootUrl$sourceHref"
        
        val isDir = sourceHref.endsWith("/")
        val cleanHref = if (isDir) sourceHref.substring(0, sourceHref.length - 1) else sourceHref
        val parentHref = cleanHref.substring(0, cleanHref.lastIndexOf('/') + 1)
        
        // Nextcloud requires properly encoded destinations
        val encodedNewName = java.net.URLEncoder.encode(newName, "UTF-8").replace("+", "%20")
        val destPath = "$parentHref$encodedNewName" + (if (isDir) "/" else "")
        val destUrl = "$rootUrl$destPath"

        val request = Request.Builder()
            .url(sourceUrl)
            .addHeader("Authorization", credentials)
            .addHeader("Destination", destUrl)
            .addHeader("Overwrite", "F")
            .method("MOVE", null)
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                runOnMain { onFailure(e) }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (response.isSuccessful || response.code == 201 || response.code == 204) {
                    runOnMain { onSuccess() }
                } else {
                    runOnMain { onFailure(Exception("HTTP Error: ${response.code}")) }
                }
            }
        })
    }
}
