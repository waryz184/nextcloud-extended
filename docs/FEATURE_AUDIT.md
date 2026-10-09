# Feature audit: Nextcloud Android 35.0.1 vs. Nextcloud Extended

Inventory of what the official client ships (read from the decompiled 35.0.1 APK: activities, fragments, the
`com.owncloud.android.lib.resources.*` operation classes and the endpoint strings in the DEX) against what
Nextcloud Extended offers. See [NETWORK_AUDIT.md](NETWORK_AUDIT.md) for the protocol-level comparison.

Legend: ✅ present · 🆕 added in this round · 🟡 partial · ❌ not implemented

## Account & security

| Feature | Official | Extended |
|---|---|---|
| Password / app-password login | ✅ | ✅ |
| QR login (`nc://login/…`) | ✅ | ✅ |
| Browser login (Login flow v2: 2FA, SSO, passkeys) | ✅ | 🆕 |
| Server check (`status.php`, maintenance, untrusted domain, redirect fix-up) | ✅ | 🆕 |
| Real account id from `cloud/user` | ✅ | 🆕 |
| Multiple accounts | ✅ | ✅ |
| App lock (biometrics / device credential) | ✅ | ✅ |
| Self-signed / private-CA certificates (trust on first use) | ✅ | 🆕 (pinned SHA-256, changed-certificate warning) |
| Client certificates, proxy settings | ✅ | ❌ |
| Remote wipe, terms-of-service signing | ✅ | ❌ |
| Push notifications (Firebase / UnifiedPush) | ✅ | ❌ (by design: no third-party services) |

## Files

| Feature | Official | Extended |
|---|---|---|
| Browse, create folder, rename, move, copy, delete | ✅ | ✅ |
| Sort options, grid view | ✅ | ❌ (folders first, A→Z) |
| Upload (picker, share-sheet, camera, document scan → PDF) | ✅ | ✅ |
| Chunked + resumable upload for large files | ✅ | 🆕 |
| Collision handling on upload | ✅ (ask / rename / overwrite / skip) | 🆕 keep-both by default, overwrite when saving an edited document |
| Resumable download straight to disk | ✅ | 🆕 |
| Offline files | ✅ files + folders, two-way sync | 🟡 single files (now unlimited size) |
| Offline queue for rename/delete/new folder | ✅ | ✅ replayed by WorkManager, per account |
| Favourites (mark + list) | ✅ | 🆕 |
| Trash bin (list, restore, delete, empty) | ✅ | 🆕 |
| File versions (list, restore) | ✅ | 🆕 |
| Search by name across the account | ✅ (WebDAV SEARCH) | 🆕 |
| Unified search (apps, mail, talk…) | ✅ | ❌ |
| Thumbnails / previews in lists | ✅ | ❌ (endpoint wired in the DocumentsProvider only) |
| Media gallery, albums, live photos | ✅ | ❌ |
| Tags, comments, file locking | ✅ | ❌ |
| Group folders / external storage | ✅ | ✅ (they are ordinary folders over WebDAV) |
| End-to-end encrypted folders | ✅ | ❌ |
| In-app PDF / Office viewers | ✅ | ✅ (large documents now open in an external app instead of being loaded into memory) |
| Collabora / OnlyOffice direct editing | ✅ | ✅ |
| Create new document from template, text editor | ✅ | ❌ |
| Auto-upload | ✅ many folders, rename rules, delete-after-upload | 🟡 photos + videos into one folder; now content-triggered, keeps real dates, never skips items |
| Android file-picker integration (DocumentsProvider) | ✅ | ✅ rewritten: seekable, thumbnails, rename/move/copy, real folder creation |

## Sharing

| Feature | Official | Extended |
|---|---|---|
| Public link | ✅ | ✅ |
| Link password, expiry, edit permission | ✅ | 🆕 |
| Share with user / group / e-mail (sharee search) | ✅ | 🆕 |
| Change permissions of an existing share, revoke | ✅ | 🆕 (permissions) / ✅ (revoke) |
| Note to recipient, hide download, download limit, federated shares | ✅ | 🟡 API calls exist, no UI |

## Server integration

| Feature | Official | Extended |
|---|---|---|
| Activity feed | ✅ | 🆕 |
| Notifications (list, dismiss, run action) | ✅ | 🆕 (pull only) |
| Storage quota display | ✅ | 🆕 |
| User status, avatar, profile | ✅ | ❌ |
| Dashboard widgets, Assistant (AI), Talk entry points | ✅ | ❌ |
| Contacts: sync to Android + backup/restore | ✅ backup/restore | ✅ sync to the system Contacts app, edit in-app |
| Calendar: backup/import | ✅ | ❌ (own calendar + widgets instead) |
| Notes, Tasks, Calendar apps | ❌ (delegates to other apps) | ✅ built-in (Extended-only) |

## What this round added to Extended

Browser login, server probe, certificate trust, chunked/resumable transfers, favourites, trash bin, versions,
server-side search, richer sharing (people, password, expiry, permissions), activity, notifications, quota —
all on top of the rewritten network layer. The Extended-only modules (calendar, tasks, notes, contacts) were
moved onto the same layer and stopped destroying data they do not model.

## Candidates for a next round (largest user value first)

1. Foreground notification + progress for long transfers (also lifts Android's ~10 min job limit).
2. List thumbnails and a media gallery (the preview endpoint is already implemented).
3. Folder-level offline sync.
4. Multi-folder auto-upload with rename rules.
5. Tags / comments / locking, unified search.
