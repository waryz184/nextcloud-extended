# Privacy Policy — Nextcloud Extended

_Last updated: 15 August 2026_

Nextcloud Extended is a native Android client for self-hosted Nextcloud servers.
Your privacy is straightforward: **we do not collect, store, or transmit any of your data to us
or to any third party.**

## What data the app handles

- **Server credentials** (server URL, username, password): entered by you to connect to **your own**
  Nextcloud server. They are stored **only on your device**, encrypted at rest using Android's
  `EncryptedSharedPreferences` (AES-256). They are never sent anywhere except to the Nextcloud
  server URL you provide, over an encrypted HTTPS connection.
- **Your content** (calendar events, tasks, notes, files): read from and written to **your own
  Nextcloud server only**, using standard protocols (CalDAV, WebDAV, and the Nextcloud Notes API).
  None of this content passes through any server controlled by us. In-app preview is limited to
  25 MB per file; larger files are handled by the system download manager.

## What we do NOT do

- No analytics, telemetry, tracking, or advertising SDKs.
- No third-party data sharing.
- No accounts, no cloud backup of your data by us (Android auto-backup is disabled).
- No data collection of any kind on our side — the app has no backend.

## Network security

The app exclusively uses **HTTPS** connections to your server. Plain HTTP connections are not
supported.

## Permissions

The app requests a single permission: **Internet access** (`android.permission.INTERNET`), required
to communicate with your Nextcloud server.

## Data deletion

All app data is stored locally on your device. Uninstalling the app, or using "Log out" in the app,
removes the stored credentials from your device. Content on your Nextcloud server is managed by you
through your own server.

## Contact

For questions about this policy, open an issue on the project's GitHub repository.
