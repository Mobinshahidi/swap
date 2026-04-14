[app]

title = LAN Share
package.name = lanshare
package.domain = org.lanshare

source.dir = .
source.include_exts = py
source.exclude_exts =
source.exclude_dirs = .git,__pycache__,bin,.github
source.exclude_patterns =

version = 1.0.0

requirements = python3,kivy,android

presplash.filename =
icon.filename =

orientation = portrait
fullscreen = 0

services =

android.permissions = INTERNET,READ_EXTERNAL_STORAGE,WRITE_EXTERNAL_STORAGE
android.api = 33
android.minapi = 21
android.ndk = 25b
android.ndk_api = 21
android.archs = arm64-v8a,armeabi-v7a

android.entrypoint = org.kivy.android.PythonActivity
android.activity_class_name = org.kivy.android.PythonActivity
android.apptheme = @android:style/Theme.NoTitleBar

android.accept_sdk_license = True
android.enable_androidx = True

android.logcat_filters = *:S python:D

p4a.bootstrap = sdl2
p4a.branch = v2024.01.21

[buildozer]

log_level = 2
warn_on_root = 1
build_dir = .buildozer
bin_dir = ./bin
