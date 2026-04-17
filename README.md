# Swap

<p align="center">
  <img src="icon.png" alt="Swap Logo" width="120" height="120" />
</p>

<p align="center">
  <b>Swap</b> is a Kotlin Android LAN file sharing app.
  <br/>
  Your phone runs a local HTTP server, and anyone on the same Wi-Fi can open the URL in a browser to upload, download, and manage files.
</p>

---

## What Swap Does

- Starts a local HTTP server on your phone (default port `1390`)
- Shows a browser URL like `http://192.168.1.5:1390`
- Generates a QR code for easy connection from another device
- Serves a web file manager UI (upload, download, zip, queue download, paste text)
- Supports Unicode filenames (Persian/Arabic/Chinese/etc.)
- Supports optional password-protected files
- Runs in a foreground service so it keeps running in background

---

## Download Release Build

You can get APK builds from GitHub Actions artifacts.

1. Open **Actions** tab in this repository.
2. Open the latest **Android Build** run.
3. Download artifact:
   - `swap-release-apk` (signed release, if signing secrets are configured)
   - `swap-debug-apk` (fallback debug build)

If you publish GitHub Releases, you can also attach `swap-release.apk` there for one-click download.

---

## App UI Overview

- URL display and status (`running` / `stopped`)
- Port input + Start/Restart
- Stop server button
- Open in browser button
- Change shared folder (SAF picker)
- Hotspot settings shortcut
- QR mode label (`Server URL` / `None`)

---

## Web UI Features

- Folder navigation + breadcrumbs
- Upload (click or drag & drop)
- Paste text card (save `.txt`, optional password)
- Search box
- Sort options:
  - Name A-Z / Z-A
  - Size small-large / large-small
  - Date newest / oldest
- Multi-select toolbar:
  - Download as ZIP
  - Queue download
- Auto-refresh when folder content changes

---

## Share-to-Swap (Android Share Menu)

Swap appears in Android's **Share** sheet (`ACTION_SEND`).

When you share a file to Swap, it is imported into the current shared root folder.

---

## Technical Stack

- **Language:** Kotlin
- **Min SDK:** 26
- **Target SDK / Compile SDK:** 34
- **Build:** Gradle Kotlin DSL
- **Server:** raw `ServerSocket` + Kotlin coroutines (`Dispatchers.IO`)
- **No HTTP server framework** (no Ktor/NanoHTTPD/OkHttp server)
- **QR generation:** ZXing core

---

## Permissions

Swap declares and uses:

- `INTERNET`
- `READ_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE` (legacy APIs)
- `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` / `READ_MEDIA_AUDIO`
- `MANAGE_EXTERNAL_STORAGE` (Android 11+ flow)
- Foreground service permissions

---

## Build in GitHub Actions (Recommended)

Workflow file: `.github/workflows/build.yml`

### Required secrets for signed release APK

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

If these are set, Actions builds signed release with:

```bash
./gradlew assembleRelease
```

Otherwise it falls back to debug build.

### Create keystore and base64 (Fedora)

```bash
sudo dnf install java-17-openjdk-devel -y

keytool -genkeypair -v \
  -keystore swap-release.jks \
  -alias swap \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000

base64 -w 0 swap-release.jks > keystore.base64.txt
```

Use content of `keystore.base64.txt` for `ANDROID_KEYSTORE_BASE64` secret.

---

## Local Development

```bash
./gradlew assembleDebug
```

Install:

```bash
adb install -r app/build/outputs/apk/debug/*.apk
```

---

## Project Structure

```text
.github/workflows/build.yml
app/src/main/java/com/lanshare/app/
  MainActivity.kt
  ServerService.kt
  HttpServer.kt
  HtmlRenderer.kt
  PasswordManager.kt
  FileUtils.kt
  QrCodeGenerator.kt
app/src/main/res/
app/build.gradle.kts
build.gradle.kts
settings.gradle.kts
```

---

## Security Notes

- Use Swap on trusted local networks.
- Password-protected files use salted SHA-256 hash entries in `.passwords.json`.
- Path traversal is blocked by server path normalization.

---

## Troubleshooting

- **Server URL not ready**: grant storage permission, then tap Start/Restart.
- **APK install conflict**: uninstall old package if signatures differ.
  - `adb uninstall com.swap.app`
- **No release artifact**: verify all 4 signing secrets are set correctly.

---

## License

This project is licensed under the MIT License.

- Full text: `LICENSE`
- Copyright: `2026 Mobin Shahidi`
