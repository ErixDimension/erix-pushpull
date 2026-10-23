
# 🗺️ ARCHITECTURE — Terang Bulan

Peta lengkap. AI WAJIB tahu.

## Struktur File

- MainActivity.kt               — UI utama + log
- data/FileEntry.kt             — model Local/Remote
- manager/LocalFileManager.kt   — file lokal A
- manager/AdbFileManager.kt     — file remote via ADB
- ptp/AdbLibUsbTransport.kt     — MARKER, inti ADB
- ptp/AdbUsbTransport.kt        — MARKER, wrapper
- adb/AdbDeviceWatcher.kt       — MARKER, watcher

## Stack Final

- AGP 8.9.1
- Kotlin 2.3.20
- minSdk 26
- Java target 1.8
- ADB: com.tananaev:adblib:1.3
- UI: com.google.android.material:1.11.0
- RecyclerView 1.3.2

## Alur Runtime

MainActivity:
- AdbDeviceWatcher.start(context)
- registerReceiver USB_ATTACHED/DETACHED
- scanExistingDevices() untuk auto-reconnect

Connect:
- AdbUsbTransport(context, device).connect()
- AdbLibUsbTransport.connect() handshake RSA
- popup RSA muncul di device B

Browser:
- Tab LOCAL  -> LocalFileManager.list(path)
- Tab REMOTE -> AdbFileManager.list(path)
- AdbFileManager -> transport.exec("ls -la --color=never path/")

## Roadmap

- [x] Stage 1: Model + LocalFileManager + FileAdapter
- [x] Stage 2a: AdbFileManager + exec()
- [x] Stage 2b: BrowserActivity + BrowserFragment
- [x] Fix: crash back, state persist, auto-reconnect
- [ ] Stage 2c: Transfer single file + progress
- [ ] Stage 2d: Chunked resume + multi-file

## JANGAN LAKUKAN

- Patch tambal dengan sed/awk/perl
- Ubah 3 file marker tanpa rewrite penuh
- Ubah versi AGP/Kotlin/library tanpa alasan
- Rename folder ptp/ atau package com.erix.ptp
- Commit tanpa backup
- Push tanpa verify brace balance

---

Versi: 1.0
Diperbarui: 2026-10-24
