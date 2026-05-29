# Nextcloud Extended

A native Android client for self-hosted Nextcloud servers. It brings your **calendar, tasks,
notes and files** together in a single app, built with **Kotlin** and **Jetpack Compose**
(Material 3). It talks directly to standard Nextcloud protocols — CalDAV, WebDAV and the Notes
API — so there is no backend in between: your data only ever travels between your device and your
own server.

The app is **bilingual (English / French)**: pick your language on the login screen, or let it
follow your device locale on first launch.

---

## Features

### 📅 Calendar (CalDAV)
- Day, week, month and year views.
- Multiple calendars with their server-defined colours, toggled on/off individually.
- Create, edit and delete events; tap an event for a detail sheet with time, location and notes.

### ✅ Tasks (CalDAV)
- Browse, create, rename and delete task lists.
- Create, edit, complete and delete tasks, with optional due dates (date picker).
- Search within a list.

### 📝 Notes (Notes API)
- Create, edit and delete notes with categories and favourites.
- Markdown rendering for viewing.
- Full-text search.

### 📁 Files (WebDAV)
- Browse your storage with folder navigation.
- Upload, download, rename and delete files; create folders.
- Generate public share links and open files in other apps.
- Search within the current folder.

---

## Privacy & Security

- **No data collection** — no analytics, no telemetry, no third-party SDKs, no backend of ours.
- Credentials are stored **encrypted on-device** (`EncryptedSharedPreferences`, AES-256).
- **HTTPS is enforced by default.** Plain HTTP is an opt-in in the advanced options, intended only
  for a server on a trusted local network.
- A single permission is requested: Internet.

See [PRIVACY.md](PRIVACY.md) for the full policy.

---

## Getting started

Install the latest signed APK from the [Releases](https://github.com/waryz184/nextcloud-extended/releases)
page. A Google Play Store release is planned.

On first launch, enter your **server URL**, **username** and **password**. Using a dedicated
[Nextcloud app password](https://docs.nextcloud.com/server/latest/user_manual/en/session_management.html#managing-devices)
is recommended rather than your main account password.

---

## Architecture & Tech

- **Language:** Kotlin (JVM 17)
- **UI:** Jetpack Compose, Material 3
- **Networking:** OkHttp — custom CalDAV / WebDAV / JSON clients
- **Parsing:** native `XmlPullParser` for WebDAV multi-status responses; lightweight in-app Markdown rendering
- **Min SDK:** 26 (Android 8.0) · **Target SDK:** 35

---

## Building from source

### Prerequisites
- JDK 17
- Android SDK (API 26+)

### Debug build
```bash
./gradlew assembleDebug
```
Output: `app/build/outputs/apk/debug/app-debug.apk`

### Release build
Release builds are minified (R8) and signed from a `keystore.properties` file at the project root
(gitignored, not included). Without it, the release build runs unsigned. Build with:
```bash
./gradlew assembleRelease
```

---

## Authors

Created and maintained with ❤️ by **waryz184** and **Hermes AI**.

---

## License

Licensed under the **Apache License 2.0**. See [LICENSE](LICENSE).

Copyright © 2026 Luna (lun-a.xyz)
