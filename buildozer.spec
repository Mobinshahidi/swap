[app]
title = LAN Share
package.name = lanshare
package.domain = org.lanshare
source.dir = .
source.include_exts = py
source.exclude_dirs = .git,__pycache__,bin,.github,.buildozer
version = 1.0.0

# Do NOT put python3 here — Buildozer adds it automatically
# Do NOT put 'android' here unless you use android.* APIs directly in requirements
requirements = kivy==2.3.0,android

orientation = portrait
fullscreen = 0

android.permissions = INTERNET,READ_EXTERNAL_STORAGE,WRITE_EXTERNAL_STORAGE
android.api = 33
android.minapi = 24
android.ndk = 25c
android.ndk_api = 24
android.archs = arm64-v8a,armeabi-v7a
android.accept_sdk_license = True
android.enable_androidx = True
android.entrypoint = org.kivy.android.PythonActivity
android.apptheme = @android:style/Theme.NoTitleBar

# Do NOT pin p4a.branch — let Buildozer use the version it knows works
# with the kivy version you specified above

[buildozer]
log_level = 2
warn_on_root = 1
