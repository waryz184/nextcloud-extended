# Network audit: Nextcloud Android 35.0.1 vs. Nextcloud Extended

This document records what the official client does on the wire (reverse-engineered from the
`com.nextcloud.client` 35.0.1 / build 350000190 APK), what Nextcloud Extended did before this work, and
what changed. Nothing here was captured from a live session: the official client was **decompiled**
(jadx 1.5.1, ~19 800 classes, 128 of which jadx could not fully decompile) and read statically. Where a
behaviour is inferred rather than read in code it is marked *(inferred)*.

Class names below are the official client's (`com.owncloud.android.lib.*` is Nextcloud's Android
library, `com.nextcloud.client.*` the app).

---

## 1. How the official client talks to the server

### 1.1 HTTP stack

| Aspect | Official behaviour | Source |
|---|---|---|
| Client | OkHttp for the new `NextcloudClient`; a legacy Commons-HttpClient + Jackrabbit stack (`OwnCloudClient`) still carries the WebDAV operations | `NextcloudClient`, `OwnCloudClient` |
| User-Agent | `Mozilla/5.0 (Android) Nextcloud-android` on every request (also what names the device in *Devices & sessions*) | `OwnCloudClientManagerFactory` |
| Timeouts | OkHttp default: connect 60 s / read 60 s / call 120 s. Per-operation `SessionTimeOut(read 60 s, connect 15 s)`. Existence check 50 s. | `NextcloudClient.createDefaultClient`, `SessionTimeOutKt`, `ExistenceCheckRemoteOperation` |
| Size-scaled timeouts | Download read timeout = clamp(180 s per GB, 60 s, 30 min). Chunk **assembly** (`MOVE .file`) timeout = clamp(180 s per GB, 30 s, 30 min) | `DownloadFileRemoteOperation`, `ChunkedFileUploadRemoteOperation` |
| Cookies | Ignored (`CookieJar.NO_COOKIES` / `CookiePolicy.IGNORE_COOKIES`) | |
| Redirects | **Followed manually**, max 3 hops, only 301/302/307, same method and body re-issued, `Destination` header rewritten (`…/remote.php/dav` is re-based on the new `Location`). SAML/IdP redirects (`Location` containing `saml` / `wayf`) become *UNAUTHORIZED* | `OwnCloudClient.followRedirection`, `NextcloudClient.followRedirection`, `RemoteOperationResult.isIdPRedirection` |
| IPv6 | On `ConnectException`, timeout or 5xx for a host that resolved IPv6-first, the request is replayed forcing IPv4 (`DNSCache`) | `OwnCloudClient.executeMethod` |
| Connectivity | `ConnectivityManager` network callback (15 s debounce) **plus** a walled-garden probe `GET <server>/index.php/204`, cached for 10 min; an upload is refused while the internet is "walled" | `ConnectivityServiceImpl`, `WalledCheckCache` |
| TLS | Custom trust manager + "known servers" store: a self-signed / private-CA certificate is shown to the user and, if accepted, remembered; client certificates supported; a `400` removes the client certificate | `AdvancedX509TrustManager`, `AdvancedX509KeyManager` |

### 1.2 URLs and identity

* Base URI keeps any sub-folder (`https://host/nextcloud`); the DAV roots are
  `…/remote.php/dav`, `…/remote.php/dav/files/<userId>`, `…/remote.php/dav/uploads/<userId>`.
* `<userId>` is the **server-side id** returned by `GET /ocs/v2.php/cloud/user?format=json`
  (`GetUserInfoRemoteOperation`), URL-encoded with `UserIdEncoder`. It is not assumed to equal the login name.
* Paths are encoded with `Uri.encode(path, "/")` (segments percent-encoded, slashes kept).
* Login URL clean-up (`AuthenticatorUrlUtils`): strips `/remote.php/dav`, `/index.php`, `/index.php/apps/…`, trailing slashes.

### 1.3 Authentication & login

1. `GET <url>/status.php` (`GetStatusRemoteOperation`): tries `https://` first, `http://` only as a fallback;
   follows redirects and **keeps the final URL**; `400` with JSON `code: 15` = untrusted domain;
   `installed:false` / non-JSON = "not a Nextcloud".
2. **Login flow v2** (`AuthenticatorActivity`): `POST <url>/index.php/login/v2` →
   `{poll:{token,endpoint}, login}`; the user signs in in a WebView/browser; the app polls
   `POST <endpoint> token=…` until it returns `{server, loginName, appPassword}`.
3. QR: `nc://login/user:<u>&password:<p>&server:<s>`.
4. After login: `GET /ocs/v2.php/cloud/user`, `GET /ocs/v2.php/cloud/capabilities`, avatar, push registration.
5. `DELETE /ocs/v2.php/core/apppassword` revokes the device's app password on account removal.
6. A remote-wipe request is polled through `POST /index.php/core/wipe/check` and acknowledged with
   `core/wipe/success` (`CheckRemoteWipeRemoteOperation`, `RemoteWipeSuccessRemoteOperation`); credentials are
   re-checked on a `401` *(inferred from the operation names, the exact trigger was not traced)*.

### 1.4 Error model (`RemoteOperationResult`)

Every operation ends in a `ResultCode` rather than a string: `UNAUTHORIZED` (401), `FORBIDDEN` (403),
`FILE_NOT_FOUND` (404), `CONFLICT` (409), `LOCKED` (423), `INSTANCE_NOT_CONFIGURED` (500),
`MAINTENANCE_MODE` (503), `QUOTA_EXCEEDED` (507), `SYNC_CONFLICT` (412 on upload), `TIMEOUT`,
`HOST_NOT_AVAILABLE`, `WRONG_CONNECTION`, `SSL_ERROR`, `SSL_RECOVERABLE_PEER_UNVERIFIED`,
`NO_NETWORK_CONNECTION`, `INVALID_CHARACTER_DETECT_IN_SERVER`, `VIRUS_DETECTED`, `SIGNING_TOS_NEEDED`
(the last three come from the Sabre `<s:exception>` XML body), `PARTIAL_MOVE_DONE` (207), …

### 1.5 File operations

| Operation | Wire behaviour |
|---|---|
| List | `PROPFIND Depth:1` with `getcontentlength, getlastmodified, getetag, resourcetype, getcontenttype, creationdate, displayname` + `oc:id, oc:fileid, oc:permissions, oc:favorite, oc:size, oc:owner-id, oc:owner-display-name, oc:comments-unread, oc:share-types` + `nc:has-preview, nc:sharees, nc:lock*, nc:system-tags, nc:mount-type, nc:is-encrypted, nc:rich-workspace, …` |
| ETag | read from `OC-ETag` then `ETag`; quotes **and a trailing `-gzip`** (Apache mod_deflate) removed |
| Existence | `HEAD`; 200/401/403 count as "exists"; 50 s timeouts |
| Create folder | `MKCOL`; **405 = already exists**; **409 = create the parent** and retry |
| Delete | `DELETE`; **404 counts as success** (idempotent) |
| Move / copy | `MOVE`/`COPY` with `Destination` + `Overwrite`; source==target is a no-op; moving into a descendant refused client-side; 207 → `PARTIAL_MOVE_DONE`; 412 without overwrite → `INVALID_OVERWRITE` |
| Upload (small) | `PUT /remote.php/dav/files/<uid>/<path>` with `OC-Total-Length`, `X-OC-Mtime` (source mtime), `X-OC-Ctime`, and `If-Match: "<etag>"` **only** when replacing a known version; retries disabled (`DefaultHttpMethodRetryHandler(0,false)`) |
| Upload (large) | **Chunked v2**: `MKCOL …/uploads/<uid>/<md5(file)>` (header `Destination`) → `PROPFIND` the folder to learn which chunks are already stored → `PUT …/<%06d>` for the remaining chunks → `MOVE …/.file` onto the destination with `X-OC-Mtime`. Chunk = server `files.chunked_upload.max_size` (≥ 10.24 MB), else 40.96 MB on Wi-Fi / 10.24 MB on mobile. Files ≤ one chunk use the plain `PUT`. |
| Name collision | `HEAD` first; user policy `SKIP / RENAME / OVERWRITE / ASK_USER`; a `412` on `PUT` becomes `SYNC_CONFLICT` |
| Pre-flight (`UploadFileOperation.checkConditions`) | Wi-Fi-only, charging-only, power-save, source still exists, connected **and not walled**, enough local space for the staged copy |
| Download | `GET` streamed into a temp file; `Last-Modified` and ETag read from the response |
| Trash | `PROPFIND/MOVE/DELETE` on `/remote.php/dav/trashbin/<uid>/trash` (+ `restore`) *(operation names and endpoints read; verbs inferred)* |
| Versions | `PROPFIND` `/remote.php/dav/versions/<uid>/versions/<fileId>`, restore by `MOVE` to `…/restore/target` |
| Search | WebDAV `SEARCH` (`d:basicsearch`, `d:like` on `displayname`) |
| Shares | OCS Share API v1 (`/ocs/v2.php/apps/files_sharing/api/v1/shares`): create/update/delete, password, expiry, permissions, note, label, hide-download; sharees search |

### 1.6 Background work

* WorkManager everywhere; uploads/downloads run expedited with a foreground notification.
* `BackoffPolicy.LINEAR`, 300 s for auto-upload and offline operations.
* Auto-upload is driven by a **content-URI trigger** (`ContentObserverWork`) plus a periodic rescan, not by a timer.
* Offline operations (rename/delete/mkdir while offline) are replayed by a periodic worker every 5 minutes.
* Offline sync of the files/folders marked "available offline" runs only on an unmetered network (`OfflineSyncWork`).

---

## 2. Findings in Nextcloud Extended (before this work)

Severity: **A** = data loss / feature unusable, **B** = fails in common real-world setups, **C** = robustness / UX.

| # | Sev | Finding | Status |
|---|---|---|---|
| 1 | A | Re-upload sent `If-None-Match: <etag>` **and** `If-Match: <etag>` — contradictory, the first one makes the server answer 412 for exactly the version being replaced | fixed |
| 2 | A | A name collision silently **overwrote** the remote file (queue, auto-upload and share-sheet uploads sent a bare `PUT`) | fixed: create-only (`If-None-Match: *`) + "keep both" |
| 3 | A | Queued uploads/downloads were wrapped in a fixed 90 s `withTimeout` → any file that needs more than 90 s failed, was retried 5× and then abandoned | fixed |
| 4 | A | Downloads (`DownloadWorker`, offline cache) went through a `ByteArray` with a 25 MB cap | fixed: streamed, resumable `.part` |
| 5 | A | No chunked upload: files above the reverse-proxy limit (e.g. 100 MB behind Cloudflare) can never upload; no resume | fixed |
| 6 | A | Calendar/task/contact edits rebuilt the whole resource from five fields: **reminders, attendees, categories, sub-task links, time zones and `X-` properties were deleted** by a simple title edit | fixed: read-modify-write merge |
| 7 | A | Events/tasks were addressed as `<uid>.ics`; items created by other clients (random file names) could not be deleted (404) and an edit created a **duplicate** | fixed: real `href` + `If-Match` |
| 8 | A | Contact sync: an empty/partial answer from the server (proxy page, truncated parse) deleted **all** local contacts; local edits were overwritten by the pull | fixed: deletion guard, dirty contacts are pushed not pulled |
| 9 | B | `301/302` on `PUT/MOVE/MKCOL` were turned into `GET` by OkHttp — a silent "success" that did nothing | fixed: manual redirects, method + body preserved, `Destination` rewritten, credentials dropped cross-origin, HTTPS downgrade refused |
| 10 | B | Sub-folder installs (`https://host/nextcloud`) doubled the prefix in every request built from a server href | fixed |
| 11 | B | DAV paths were built from the **login name**; wrong for e-mail/LDAP/SSO accounts | fixed: `ocs/cloud/user` id |
| 12 | B | Login required the *Calendar* PROPFIND to succeed → login failed on servers without Calendar/with the path blocked; errors were the raw `HTTP Error: 401` | fixed: `status.php` + `cloud/user` + readable errors |
| 13 | B | First auto-upload into a not-yet-existing folder returned `409` → permanent failure | fixed: parent folders are created |
| 14 | B | XML read with regexes bound to the literal `d:` prefix; CDATA / other prefixes broke; entities decoded by hand | fixed: namespace-aware SAX, DTD/entities disabled |
| 15 | B | `URLDecoder` turned a literal `+` in a path into a space (listing, *navigate up*, folder detection) | fixed |
| 16 | B | `getShares` read `data.element`; the OCS JSON answer is a plain array → *list shares* failed against current servers | fixed |
| 17 | B | The offline-operation queue only replayed while the app was open, used the **active** account's credentials for every queued item, and one permanently refused item blocked everything behind it; a replayed `DELETE` of an already-deleted file failed forever | fixed: WorkManager replay, per-account, drop on permanent refusal, idempotent delete |
| 18 | B | Upload worker: rows stuck in `RUNNING` after a process death, head-of-line blocking on any error, `KEEP` policy could lose a wake-up | fixed |
| 19 | B | Auto-upload: 6 h inexact alarm; the "last scan" was advanced **before** uploading (failed items were lost); no original mtime → every photo appears as "uploaded today" | fixed: content-URI trigger + periodic safety net, cursor advances only past queued items, `X-OC-Mtime` |
| 20 | B | `DocumentsProvider`: `createDocument` created nothing (new folders never existed), `RECENTS`/`THUMBNAIL` flags advertised but unimplemented, a lookup listed the whole parent folder, write via a pipe failed silently and could not support read-write opens | fixed |
| 21 | B | Self-signed / private-CA servers (the usual self-hoster setup) could not connect at all | added: trust-on-first-use pinning |
| 22 | C | Every `CalDavClient` built its own `OkHttpClient` (no connection reuse, one thread pool per instance); no User-Agent | fixed: shared pool/dispatcher |
| 23 | C | A cancelled call was dropped silently, so the "loading" counter never came back to zero | fixed |
| 24 | C | No retry/back-off at all for idempotent requests; `Retry-After`, `429`, `503` ignored; maintenance mode shown as a generic failure | fixed |
| 25 | C | `ETag` with `-gzip` suffix compared unequal to the real one | fixed |
| 26 | C | Downloads went to an app-private directory the user cannot reach on Android 11+ | fixed: published to `Downloads/Nextcloud` (Android 10+) |
| 27 | C | Events: `VALARM`'s `DESCRIPTION` leaked into the event description; `SUMMARY;LANGUAGE=…:` was not recognised | fixed |
| 28 | C | IPv6-first connection to a host with broken IPv6 waited a full connect timeout on every request | mitigated: address families alternate and the one that connected last goes first |

## 3. What changed

```
data/network/
  DavSession        blocking core: URLs (+sub-folder), auth, redirects, retries/back-off, cancel, error mapping
  NextcloudHttp     shared OkHttp client, executor, address-family ordering, size-scaled timeouts
  NextcloudErrors   HttpStatusException / FailureKind / transient-vs-permanent classification
  DavMultistatus    namespace-aware SAX reader for 207 answers
  FileApi           list/stat/search/mkdir/move/copy/delete/favorite, upload (simple + chunked v2 + resume), resumable download
  OcsApi            user, capabilities, shares, sharees, notifications, activity, direct editing
  DavExtras         trash bin, versions
  GroupwareApi      CalDAV events+tasks (read-modify-write), CardDAV, Notes
  IcsSupport / VcardSupport  merge-preserving (de)serialisation
  ServerAccess      status.php probe, login flow v2, URL normalisation
  TlsTrust          user-approved certificate pinning
  CalDavClient      thin callback facade used by the UI (unchanged public surface + new calls)
upload/             workers rewritten on top of FileApi
documents/          DocumentsProvider rewritten (seekable, ranged reads, staged writes)
```

Design rules that came out of the audit:

* One failure vocabulary (`FailureKind`) decides both the message shown and whether a background job retries.
* Idempotent verbs retry with back-off; `POST`, `MOVE`, `COPY`, `MKCOL` never do (except through logic that re-checks state).
* A queue item that can never succeed is failed, not retried forever; one that cannot succeed *yet* (offline, 5xx,
  locked, maintenance) does not consume the same budget as one the server rejected.
* Nothing the user did not ask for is overwritten or deleted by a network hiccup.

## 4. Verification

* 138 JVM unit tests, including a small in-memory WebDAV server (`FakeDavServer`) and a real TLS server:
  redirects keep method/body, credentials never leave the origin, chunked upload resumes without re-sending
  stored chunks, interrupted downloads resume with `Range`+`If-Range`, lost `MOVE` answers are recognised,
  calendar edits keep alarms/attendees, contact sync never mass-deletes, a self-signed certificate is refused
  until trusted and a *changed* certificate is flagged.
* **Not verified**: nothing was run on a device or emulator (no virtualization in the build VM) and nothing was
  run against a live Nextcloud server. The behaviours above are derived from the protocol, the official client's
  decompiled code and the in-memory server; the first real-device pass should exercise login flow v2, a large
  upload over a flaky link, the DocumentsProvider and a self-signed server.

## 5. Not done / known gaps

* Long transfers still run as ordinary WorkManager jobs (no foreground notification, so Android may stop them
  after ~10 minutes — chunked uploads and ranged downloads resume where they stopped).
* No push notifications (needs Firebase; against the app's privacy stance). Notifications are pulled.
* No end-to-end-encrypted folders, no client certificates, no HTTP proxy settings.
* Calendar times: events are displayed/edited as wall-clock time without time-zone conversion (existing behaviour,
  untouched); the merge keeps the original `TZID` lines when the time was not edited.
