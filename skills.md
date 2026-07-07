# Swap

## Description
Swap is a native Kotlin Android app for LAN file sharing. The phone runs a local HTTP server (default port `1390`); any device on the same Wi-Fi opens the displayed URL (or scans a QR code) in a browser to upload, download, zip, and manage files. It uses Storage Access Framework (SAF) for a user-chosen shared folder, supports Unicode filenames and optional Basic Auth, and runs as a foreground service so it stays alive in the background.

## Tech Stack
| Layer | Technology | Why |
|-------|-----------|-----|
| Language | Kotlin | Native Android app |
| Build | Gradle (Kotlin DSL) | `build.gradle.kts`, `settings.gradle.kts` |
| HTTP server | Custom in-app server (`HttpServer.kt`) + foreground `ServerService` | Serve web file manager on LAN |
| Storage | Android SAF (DocumentsContract) | Scoped, permission-light folder access |
| UI | Android Views (no Compose) | `activity_main.xml` + `MainActivity` |
| Web UI | Server-rendered HTML (`HtmlRenderer.kt`) | Browser-side file manager |
| QR | `QrCodeGenerator.kt` | Easy connect from another device |
| Distribution | GitHub Actions, F-Droid metadata, fastlane | CI builds + store listing |

## Architecture
The app is a single Activity that configures a port and shared folder, then starts a foreground `ServerService` hosting a hand-written HTTP server. Incoming browser requests are routed by the server to handlers that read/write files through SAF (`FileUtils`) and render a web UI via `HtmlRenderer`. Optional `PasswordManager` adds per-file/server Basic Auth.

### File Map
- `app/src/main/java/com/lanshare/app/MainActivity.kt` (~298 lines) — UI: URL/status display, port input, start/stop/restart, SAF folder picker, open-in-browser, QR mode, hotspot shortcut.
- `app/src/main/java/com/lanshare/app/HttpServer.kt` (~500 lines) — The LAN HTTP server: request parsing, routing, upload (multipart, Unicode-safe), download, zip/queue, text paste.
- `app/src/main/java/com/lanshare/app/ServerService.kt` (~193 lines) — Foreground service that owns the server lifecycle so it survives backgrounding.
- `app/src/main/java/com/lanshare/app/HtmlRenderer.kt` (~658 lines) — Builds the browser file-manager HTML/CSS/JS.
- `app/src/main/java/com/lanshare/app/FileUtils.kt` (~381 lines) — SAF document tree operations: list, read, write, delete, mime/size.
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
5. Optional Basic Auth (`PasswordManager`) gates access. Stopping the server tears down the service.

## Key Functions / Classes
### HttpServer
- **Purpose**: Serve the LAN file-manager over HTTP.
- **Input**: Raw HTTP requests (GET/POST, multipart uploads).
- **Output**: HTML pages, file streams, zip archives.
- **Quirks**: Hand-rolled server; explicit Unicode filename handling for multipart uploads (Persian/Arabic/Chinese).

### ServerService
- **Purpose**: Keep the server running in the background.
- **Quirks**: Foreground service with persistent notification (Android background limits).

### FileUtils (SAF)
- **Purpose**: All file operations via DocumentsContract.
- **Quirks**: Recent change enforces SAF-only access and drops broad storage permissions for Play/F-Droid compliance.

### HtmlRenderer / PasswordManager / QrCodeGenerator
- Render the web UI; optional Basic Auth; generate the connect QR.

## Configuration
- Port (default `1390`) and shared folder chosen at runtime in the UI; persisted in `SharedPreferences`.
- Optional server-wide Basic Auth / per-file passwords.
- Signing via `swap-release.jks` (CI uses signing secrets; debug fallback otherwise).
- App id `com.swap.app`; package namespace `com.lanshare.app`.

## Data Flow
Browser request (upload/download/zip/paste) → `HttpServer` route → `FileUtils` SAF read/write on the shared folder → `HtmlRenderer` response → browser. Server reachable only on the local Wi-Fi network.

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
Recent commits: single-request multi-image upload + optional server-wide Basic Auth; GitHub links and version bump to 1.0.3; SAF path-display fix + GitHub icon; build fixes; removal of broad storage perms (SAF-only); Unicode multipart upload fix; F-Droid fastlane metadata and CI auto-releases.
