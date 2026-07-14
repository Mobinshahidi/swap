# Swap

## Description
Swap is a native Kotlin Android app for LAN file sharing. The phone runs a local HTTP server (default port `1390`); any device on the same Wi-Fi opens the displayed URL (or scans a QR code) in a browser to upload, download, zip, and manage files. It uses Storage Access Framework (SAF) for a user-chosen shared folder, supports Unicode filenames and optional Basic Auth, and runs as a foreground service so it stays alive in the background.

## Tech Stack
| Layer | Technology | Why |
|-------|-----------|-----|
| Language | Kotlin | Native Android app |
| Build | Gradle (Kotlin DSL) | `build.gradle.kts`, `settings.gradle.kts` |
| HTTP server | Custom in-app server (`HttpServer.kt`) + foreground `ServerService` | Serve web file manager on LAN; HTTP/1.1 keep-alive, TCP_NODELAY, buffered I/O, Range requests |
| Discovery | jmdns (`_http._tcp`) | Reach the share at `http://swap.local:<port>` |
| Storage | Android SAF (DocumentsContract) | Scoped, permission-light folder access |
| UI | Android Views (no Compose) | `activity_main.xml` + `MainActivity` |
| Web UI | Server-rendered HTML (`HtmlRenderer.kt`) | Browser-side file manager |
| QR | `QrCodeGenerator.kt` | Easy connect from another device |
| Distribution | GitHub Actions, F-Droid metadata, fastlane | CI builds + store listing |

## Architecture
The app is a single Activity that configures a port and shared folder, then starts a foreground `ServerService` hosting a hand-written HTTP server. Incoming browser requests are routed by the server to handlers that read/write files through SAF (`FileUtils`) and render a web UI via `HtmlRenderer`. Optional `PasswordManager` adds per-file/server Basic Auth.

`ServerService` holds a partial `WakeLock` + Wi-Fi `WifiLock` for the server's lifetime so the phone's CPU/radio don't enter power-save while serving (this is what keeps LAN latency in the millisecond range instead of seconds). The server keeps each browser connection alive (HTTP/1.1 keep-alive) and serves multiple requests per socket. The web UI detects folder changes by polling a tiny `/__snapshot` token every 4s over that reused connection (no held-open streams), and reloads when the token changes.

### File Map
- `app/src/main/java/com/lanshare/app/MainActivity.kt` (~298 lines) — UI: URL/status display, port input, start/stop/restart, SAF folder picker, open-in-browser, QR mode, hotspot shortcut.
- `app/src/main/java/com/lanshare/app/HttpServer.kt` (~520 lines) — The LAN HTTP server: keep-alive connection loop, request parsing, routing, upload (multipart, Unicode-safe), download, zip, text paste, `/__snapshot` change token. Per-socket `TCP_NODELAY` + 20s idle timeout, 64KB buffered output.
- `app/src/main/java/com/lanshare/app/ServerService.kt` (~230 lines) — Foreground service that owns the server lifecycle so it survives backgrounding; acquires/releases a partial `WakeLock` + `WifiLock` (LOW_LATENCY/HIGH_PERF) around the running server.
- `app/src/main/java/com/lanshare/app/HtmlRenderer.kt` (~655 lines) — Builds the browser file-manager HTML/CSS/JS; includes the 4s `/__snapshot` change poller.
- `app/src/main/java/com/lanshare/app/FileUtils.kt` (~355 lines) — SAF document tree operations: list, read, write, mime/size. Directory listing reads name/type/size/mtime in a single cursor query (`listChildDocs`) so large folders load fast.
- `app/src/main/java/com/lanshare/app/PasswordManager.kt` (~68 lines) — Optional Basic Auth / password-protected files.
- `app/src/main/java/com/lanshare/app/QrCodeGenerator.kt` (~31 lines) — Generates a QR bitmap for the server URL.
- `app/src/main/AndroidManifest.xml` — Permissions, service declaration, launcher activity.
- `app/build.gradle.kts`, `build.gradle.kts`, `settings.gradle.kts`, `gradle/` — Gradle build.
- `.github/workflows/build.yml`, `release.yml` — CI build + auto GitHub releases.
- `fdroid-metadata-com.swap.app.yml`, `fastlane/metadata/android/en-US/*` — F-Droid recipe + store listing.
- `swap-release.jks`, `keystore.base64.txt` — Release signing keystore (sensitive; committed).

## How It Works
1. User opens the app, picks a shared folder via the SAF picker and a port (default 1390), and taps Start.
2. `MainActivity` launches `ServerService` as a foreground service, which starts `HttpServer` bound to the device's Wi-Fi IP.
3. The app shows `http://<ip>:1390` plus a QR code.
4. Another device opens that URL; `HttpServer` routes the request, `HtmlRenderer` serves the file-manager UI, and `FileUtils` performs uploads/downloads/zips against the SAF tree.
5. The page keeps its connection alive and polls `/__snapshot/<dir>` every 4s; when the folder's snapshot token changes (someone uploaded/deleted), it reloads automatically.
6. Optional Basic Auth (`PasswordManager`) gates access. Stopping the server releases the wake/Wi-Fi locks and tears down the service.

## Key Functions / Classes
### HttpServer
- **Purpose**: Serve the LAN file-manager over HTTP.
- **Input**: Raw HTTP requests (GET/POST, multipart uploads).
- **Output**: HTML pages, file streams, zip archives, `/__snapshot` change token.
- **Quirks**: Hand-rolled server; explicit Unicode filename handling for multipart uploads (Persian/Arabic/Chinese). One coroutine per socket runs a keep-alive request loop (`Connection: keep-alive`, `TCP_NODELAY`, 20s idle `soTimeout`, 64KB `BufferedOutputStream`). Streamed responses that can't be framed (zip) send `Connection: close`. `route()` returns a "should close" flag.

### ServerService
- **Purpose**: Keep the server running in the background.
- **Quirks**: Foreground service with persistent notification (Android background limits). Acquires a partial `WakeLock` (CPU) and a `WifiLock` (`WIFI_MODE_FULL_LOW_LATENCY` on API 29+, else `HIGH_PERF`) on start and releases them on stop/restart/destroy — without these, Wi-Fi/CPU power-save adds seconds of latency per request when the screen is off.

### FileUtils (SAF)
- **Purpose**: All file operations via DocumentsContract.
- **Quirks**: Recent change enforces SAF-only access and drops broad storage permissions for Play/F-Droid compliance. `listChildDocs()` fetches name/mime/size/mtime in ONE cursor query per directory; sorting and sizing happen in memory (no per-file IPC), so a 50+ item folder renders in one SAF round-trip instead of thousands.

### HtmlRenderer / PasswordManager / QrCodeGenerator
- Render the web UI; optional Basic Auth; generate the connect QR.

## Configuration
- Port (default `1390`) and shared folder chosen at runtime in the UI; persisted in `SharedPreferences`.
- Optional server-wide Basic Auth / per-file passwords.
- Signing via `swap-release.jks` (CI uses signing secrets; debug fallback otherwise).
- App id `com.swap.app`; package namespace `com.lanshare.app`.

## Data Flow
Browser request (upload/download/zip/paste) → `HttpServer` route → `FileUtils` SAF read/write on the shared folder → `HtmlRenderer` response → browser. Server reachable only on the local Wi-Fi network.

### Endpoints
- `GET /` and `GET /<dir>` — directory listing (HTML) or, for a file path, a download stream. Supports HTTP `Range` (206 Partial Content) for video seeking + resumable downloads; `?inline=1` serves `Content-Disposition: inline` for in-browser preview.
- `GET /__snapshot/<dir>` — tiny text token that changes when the folder's contents change; polled by the page for live refresh.
- `POST /upload/<dir>` — multipart upload (all files in one request; Unicode-safe filenames).
- `POST /paste/<dir>` — save a text note as `<name>.txt`, optionally password-protected.
- `POST /zip/<dir>` — stream a ZIP of the selected files/folders (`Connection: close`, no Content-Length).
- `POST /delete/<dir>` — delete selected files/folders (form field `files=`, repeated); also clears any per-file passwords.
- `POST /rename/<dir>` — rename one entry (JSON `{name, to}`); moves its password entry along.
- `POST /mkdir/<dir>` — create a subfolder (JSON `{name}`).
- `POST /move/<dir>` — move entries into another folder (JSON `{dest, files[]}`).
- `POST /delete/<dir>` — soft-delete into `.trash` (see Trash below).
- `GET /__search?q=` — recursive filename search across the whole tree (JSON, capped at 300).
- `GET /__thumb/<file>` — JPEG thumbnail (SAF `getDocumentThumbnail`) for gallery view.
- `GET /__stats` — session transfer counters (uploads/downloads + bytes), in-memory.
- `POST /__share/<dir>` — mint an expiring/one-time link (JSON `{name, ttl, once}` → `/s/<token>`).
- `GET /s/<token>` — download via a share token; **bypasses Basic Auth** (the token is the credential); in-memory, lost on restart.
- `GET /__trash` · `POST /__restore` · `POST /__trashempty` — list / restore / permanently empty Trash.
- `GET /<file>?pw=…` — download a per-file password-protected file.

Trash: delete moves items into a hidden root `.trash` folder with a `.trash/.index.json`
recording each item's original path; `.trash` is hidden from listings, search, and direct
GET (403). Restore moves an item back; emptying hard-deletes and purges password entries.

### Web UI features
- **File management**: multi-select toolbar (Rename / Move / Delete) + a "New folder" button; all reload via the snapshot poller so other tabs update too.
- **In-browser preview**: clicking an image/video/audio/PDF/text file opens a modal (uses `?inline=1`); videos seek via Range. A Download button in the modal keeps the original save path.
- **mDNS**: `ServerService` advertises `_http._tcp` as host `swap`, so the share is reachable at `http://swap.local:<port>` (shown in the app under the IP URL). Needs `CHANGE_WIFI_MULTICAST_STATE` + a held `MulticastLock`; best-effort (jmdns).
- **Gallery**: a grid/thumbnail toggle for image/video files (client builds tiles from `/__thumb`).
- **Recursive search**: the "🌐 All folders" button queries `/__search` and lists matches across the tree.
- **Trash + restore**: delete is undoable; the "🗑 Trash" modal restores or empties.
- **Share links**: the "🔗 Share" button copies an expiring/one-time `/s/<token>` URL that needs no password.
- **Copy**: text previews have a Copy button (`navigator.clipboard` with an `execCommand` fallback for plain-HTTP LAN).
- **Camera upload**, **dark mode** (follows `prefers-color-scheme`), and a session **transfer-stats** line in the footer.

### App (native) features
- **Check for updates** button in `MainActivity` queries the GitHub `releases/latest` API off-thread, compares to the installed `versionName`, and opens the release page if newer. (Requires internet — there is no offline way to learn about newer releases.)

## Performance Notes
Three things keep LAN loads fast (they were the fix for 20–30s page loads):
1. **Wake/Wi-Fi locks** in `ServerService` stop the radio/CPU from sleeping mid-request.
2. **Keep-alive + buffered writes + TCP_NODELAY** in `HttpServer` avoid a fresh TCP handshake and packet fragmentation per request.
3. **Single-cursor SAF listing** in `FileUtils` avoids thousands of per-file binder IPCs on large folders.
Change detection uses cheap `/__snapshot` polling (not Server-Sent Events) because `EventSource` dropped Basic-Auth credentials on reconnect (repeated 401 login prompts) and each held-open stream blocked a server thread.

## Entry Points
```bash
# build (debug)
./gradlew assembleDebug
# build (release, needs signing config/secrets)
./gradlew assembleRelease
# install to a connected device
./gradlew installDebug
# CI builds run in .github/workflows/build.yml (artifacts) and release.yml
```

## Known Issues / TODOs
- Release keystore (`swap-release.jks`, `keystore.base64.txt`) is committed to the repo — a secret-exposure concern.
- Uses a custom HTTP server (no TLS) — intended for trusted LAN use only.
- History shows SAF migration to remove broad storage permissions for F-Droid; `DependencyInfoBlock` disabled for F-Droid reproducibility.

## Recent Work (git log)
Latest: network performance overhaul — WakeLock/WifiLock in `ServerService`, HTTP/1.1 keep-alive + buffered writes + TCP_NODELAY in `HttpServer`, single-cursor SAF directory listing in `FileUtils`, and `/__snapshot` polling for live refresh (replacing Basic-Auth-incompatible SSE). This cut folder loads from ~20–30s to ~1s.
Earlier: single-request multi-image upload + optional server-wide Basic Auth; GitHub links and version bump to 1.0.3; SAF path-display fix + GitHub icon; build fixes; removal of broad storage perms (SAF-only); Unicode multipart upload fix; F-Droid fastlane metadata and CI auto-releases.
