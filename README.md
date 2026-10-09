# Nextcloud Extended

A native Android client for self-hosted Nextcloud servers. It brings your **calendar, tasks,
notes, contacts and files** together in a single app, built with **Kotlin** and **Jetpack Compose**
(Material 3). It talks directly to standard Nextcloud protocols — CalDAV, CardDAV, WebDAV, the
Notes API and OCS — so there is no backend in between: your data only ever travels between your
device and your own server.

The app is **bilingual (English / French)**: pick your language on the login screen, or let it
follow your device locale on first launch.

---

## Features

### 📊 Files (WebDAV + OCS)
- Browse your storage with folder navigation, grid/list search and configurable sorting.
- Upload, download, rename, **copy and move** files; create folders.
- **Persistent transfer queue** (Room + WorkManager) with automatic retry, progress, history,
  cancel and re-run — uploads survive process death and network loss.
- **Large files**: chunked, resumable uploads (Nextcloud chunking v2) and resumable downloads written
  straight to disk — a dropped connection continues where it stopped. A name that already exists is
  never overwritten silently: the upload is kept as "name (2).ext".
- **Favourites**, **trash bin** (restore / delete / empty), **file versions** (restore), and
  **server-side search** across the whole account.
- **Sharing**: public links with password and expiry, shares with users, groups and e-mail addresses,
  per-share permissions, revoke.
- **Activity** feed, **notifications** (accept / decline / dismiss) and a **storage quota** line.
- **Make files available offline** with a per-account cache, offline file manager and reuse of the
  cache when opening documents.
- **Offline operations queue**: deletes, renames and folder creation are replayed automatically
  when the connection returns.
- **Conflict detection** via WebDAV ETags (412/409): choose to overwrite, keep both or skip.
- Android **content provider** (read + write): files open, save back, rename and delete from
  Files-by-Google and any compatible file manager.
- Share files into the app from any Android app (single or multiple).
- Capture photos and **scan multi-page documents** straight into a single PDF.

### 📅 Calendar (CalDAV)
- Day, week, month and year views.
- Multiple calendars with their server-defined colours, toggled on/off individually.
- Create, edit and delete events; tap an event for a detail sheet with time, location and notes.
- Calendar home-screen widgets.

### ✅ Tasks (CalDAV)
- Browse, create, rename and delete task lists.
- Create, edit, complete and delete tasks, with optional due dates (date picker).
- Search within a list.

### 📝 Notes (Notes API)
- Create, edit and delete notes with categories and favourites.
- Markdown rendering for viewing.
- Full-text search.
- The tab is hidden automatically when the Notes app is not installed on your server.

### 👤 Contacts (CardDAV)
- Browse contacts across your address books, each shown with its photo or an initials avatar.
- Create, edit and delete contacts: photo, name, organization, birthday, labelled phone numbers
  and emails, postal addresses and groups.
- Tap a phone number to call, an email to compose, or an address to open it in maps.
- Search by name, phone, email, organization or group.
- Optional sync to the Android Contacts app via a system account. The setup reuses your current Nextcloud login (same server), or can be filled by scanning the Nextcloud login QR code.

### 🛡️ Accounts, security & uploads
- **Multiple accounts** with secure per-account credential storage, account switcher and strict
  isolation of queues and caches between accounts.
- Server **capability discovery** (`/ocs/v2.php/cloud/capabilities`); optional features are hidden
  when the matching Nextcloud app is absent.
- **Optional media auto-upload** (new photos and videos) with a configurable destination folder,
  Wi-Fi-only and charging-only constraints.
- **App lock** using biometrics or the device credential (AndroidX BiometricPrompt), with
  automatic screen protection while locked.

---

## Privacy & Security

- **No data collection** — no analytics, no telemetry, no third-party SDKs, no backend of ours.
- Credentials are stored **encrypted on-device** (`EncryptedSharedPreferences`, AES-256).
- **HTTPS is enforced by default.** Plain HTTP is an opt-in in the advanced options, intended only
  for a server on a trusted local network.
- Sign in with a regular password, a Nextcloud **app password**, by **scanning the Nextcloud login QR code**,
  or **through your browser** (login flow v2 — works with two-factor authentication, SSO and passkeys).
- Servers with a **self-signed or private-CA certificate** are supported: the certificate fingerprint is shown
  once, pinned if you accept, and a later *different* certificate triggers a loud warning.
- Permissions are requested only when a feature needs them: Internet, camera, media access,
  biometrics and legacy storage on older Android versions.

See [PRIVACY.md](PRIVACY.md) for the full policy.

---

## Getting started

Install the latest signed APK from the [Releases](https://github.com/waryz184/nextcloud-extended/releases)
page. A Google Play Store release is planned.

On first launch, choose how to sign in:

- **Password** — enter your **server URL**, **username** and **password**.
- **App password** (recommended, required when two-factor authentication is enabled) — generate one in
  Nextcloud under *Settings → Security → Devices & sessions → Create new app password*
  ([documentation](https://docs.nextcloud.com/server/latest/user_manual/en/session_management.html#managing-devices)),
  then either type it in, or tap **Scan Nextcloud QR code** and scan the QR code shown by
  *Show QR code for mobile apps*. The server, username and app password are filled in and the
  app signs in automatically.

---

## Architecture & Tech

- **Language:** Kotlin (JVM 17)
- **UI:** Jetpack Compose, Material 3
- **Networking:** OkHttp — one shared connection pool; a blocking `DavSession` core (redirects that keep the
  WebDAV method, retry with back-off, sub-folder installs, typed errors) under small API classes for
  WebDAV, OCS, CalDAV/CardDAV and Notes. Protocol notes: [docs/NETWORK_AUDIT.md](docs/NETWORK_AUDIT.md),
  feature comparison with the official app: [docs/FEATURE_AUDIT.md](docs/FEATURE_AUDIT.md)
- **Persistence:** Room (transfer queues, offline cache, offline operations), encrypted session
  preferences (AES-256)
- **Background:** WorkManager with network/battery constraints and unique per-account work
- **Parsing:** namespace-aware SAX for WebDAV multi-status responses; calendar and task edits are merged into
  the stored iCalendar so reminders, attendees and unknown properties survive; lightweight in-app Markdown rendering
- **Min SDK:** 26 (Android 8.0) · **Target SDK:** 35

---

## Building from source

### Prerequisites
- JDK 17
- Android SDK (API 26+)

### Tests
```bash
./gradlew testDebugUnitTest
```
The JVM suite runs the network layer against an in-memory WebDAV server and a real TLS server — no device
and no Nextcloud instance needed.

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
