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
  Nextcloud server only**, using standard protocols (CalDAV, WebDAV, CardDAV, OCS and the
  Nextcloud Notes API). None of this content passes through any server controlled by us. File
  uploads are streamed directly to your server; large downloads are written to your device's
  private storage and never leave the device except through HTTPS to your server.
- **Pending transfers and offline cache**: kept only on the device in a local app database
  (Room) and private app storage, scoped to the account that created them, and deleted after a
  successful transfer, on account removal, or from the in-app history/offline screens.

## What we do NOT do

- No analytics, telemetry, tracking, or advertising SDKs.
- No third-party data sharing.
- No accounts, no cloud backup of your data by us (Android auto-backup is disabled).
- No data collection of any kind on our side — the app has no backend.

## Network security

The app exclusively uses **HTTPS** connections to your server. Plain HTTP connections are not
supported.

## Permissions

The app requests the minimum permissions needed for the features you use:

- **Internet** — required to communicate with your Nextcloud server.
- **Camera** — only to take photos and scan documents into your Files.
- **Photos and videos** (or **legacy storage** on Android 8–12) — only when you enable
  **automatic media upload**; no media is uploaded without your explicit opt-in.
- **Use biometric** (with your device lock as fallback) — only when you enable **App lock**.

Permissions are declared in the manifest but only requested at runtime when the related
feature is activated. Content shared into the app is copied to the app's private storage before
upload so it is not lost if the sending app disappears.

## Data deletion

All app data is stored locally on your device. Uninstalling the app, or using "Log out" in the app,
removes the stored credentials from your device. Pending transfer history, offline files and the
offline operation queue can be cleared from the in-app **Transfer history** and **Available
offline** screens, and are removed for an account when you remove that account. Content on your
Nextcloud server is managed by you through your own server.

## Contact

For questions about this policy, open an issue on the project's GitHub repository.
