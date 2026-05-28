# Nextcloud Extended 📱🚀

**Nextcloud Extended** is a modern, high-performance, and native Android application designed to turn your self-hosted Nextcloud server into a powerful, unified productivity workspace on your phone.

Built completely from scratch using **Kotlin** and **Jetpack Compose**, this application integrates seamlessly with standard Nextcloud protocols and official APIs to deliver a blazing-fast, beautiful, and fluid Material 3 user experience.

---

## 🌟 Key Features

### 📅 1. Calendar / Agenda (CalDAV)
* Fully native calendar engine supporting year, month, and day views.
* Interactive agenda lists that fetch and parse `.ics` payloads dynamically.
* Fluid navigation and seamless representation of events directly from your Nextcloud calendar collections.

### 📋 2. Tasks / Todos (CalDAV)
* Fully-fledged integration with the Nextcloud Tasks application via CalDAV.
* Create, update, view, and toggle completion statuses of your lists and tasks in real-time.
* Native rendering of lists and checklists with a responsive Material Design.

### 📝 3. Notes (REST API)
* Native integration with the official Nextcloud Notes API.
* Real-time notes creation, updates, and lightning-fast full-text searches.
* Text manipulation and rich local viewing of your markdown-based cloud notes.

### 📁 4. Drive / Cloud Storage (WebDAV)
* A beautiful, interactive file explorer for your entire Nextcloud storage.
* Navigate folders recursively (with breadcrumbs or quick-back navigation).
* Create new subfolders instantly (via `MKCOL` requests).
* Delete files or folders securely from your server.
* Visual indicators including dynamic file sizes (formatted to KB/MB) and modification timestamps.

---

## 🛠️ Architecture & Technologies

* **Language**: Native Kotlin 1.9+
* **UI Toolkit**: Jetpack Compose (Declarative UI) with Material Design 3
* **Network & HTTP**: OkHttpClient (custom implementation of CalDAV, WebDAV, and JSON REST APIs)
* **XML Parsing**: Native XmlPullParser for lightweight, dependency-free processing of WebDAV multi-status XML payloads.
* **Build System**: Gradle 8.5 with Kotlin DSL

---

## 📦 How to Build and Run

To compile the application headless or in Android Studio:

### Prerequisites
* JDK 17
* Android SDK (API 26+)

### From Command Line (Gradle)
```bash
./gradlew assembleDebug
```
The compiled, debuggable APK will be generated at:
`app/build/outputs/apk/debug/app-debug.apk`

---

## 👥 Authors & Contribution

Created and maintained with ❤️ by **waryz184** and **Hermes AI**.
