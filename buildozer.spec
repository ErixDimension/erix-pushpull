[app]
title = ERIX PushPull
package.name = erixpushpull
package.domain = org.erix
source.dir = .
source.include_exts = py,png,jpg,kv,atlas,ttf,so
version = 1.0
requirements = python3==3.11.9,hostpython3==3.11.9,kivy==2.3.1,pyjnius==1.6.1,requests==2.31.0,charset-normalizer<3.0,idna==3.6,urllib3==2.2.1,certifi==2024.2.2
source.include_patterns = assets/adb,assets/lib/*
orientation = portrait
android.permissions = INTERNET,READ_EXTERNAL_STORAGE,WRITE_EXTERNAL_STORAGE,MANAGE_EXTERNAL_STORAGE
android.api = 33
android.minapi = 24
android.ndk = 25b
android.accept_sdk_license = True
android.archs = arm64-v8a

[buildozer]
log_level = 2
warn_on_root = 1
