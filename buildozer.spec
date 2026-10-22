[app]
title = ERIX Push Pull
package.name = erixpushpull
package.domain = com.erix

source.dir = .
source.include_exts = py,png,jpg,kv,atlas,ttf,so

version = 0.1

requirements = python3,kivy==2.3.1,android,pyjnius

android.permissions = INTERNET,READ_EXTERNAL_STORAGE,WRITE_EXTERNAL_STORAGE,MANAGE_EXTERNAL_STORAGE

android.api = 33
android.minapi = 24
android.ndk = 25b
android.ndk_api = 24
android.archs = arm64-v8a
android.accept_sdk_license = True
android.allow_backup = True

orientation = portrait
fullscreen = 0

android.debug_artifact = debug
android.release_artifact = release

[buildozer]
log_level = 2
warn_on_root = 1
source.include_patterns = assets/adb,assets/lib/*
