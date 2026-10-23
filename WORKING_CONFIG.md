# ⚠️ KONFIGURASI YANG TERBUKTI BEKERJA — JANGAN UBAH SEMBARANGAN

**Status:** ✅ ADB USB terhubung, popup RSA muncul, transfer siap >4GB.
**Tagged :** `working-adblib-20261023_235404`

## Stack Final

| Komponen | Versi / Nilai |
|---|---|
| AGP | 8.9.1 |
| Kotlin | 2.3.20 |
| minSdk | 26 |
| targetSdk | (cek build.gradle) |
| Java target | 1.8 |
| ADB library | **com.tananaev:adblib:1.3** |
| Transport | `UsbSocket` (bungkus UsbDeviceConnection → java.net.Socket) |
| Auth | AdbCrypto RSA 2048 + `A_AUTH(RSAPUBLICKEY)` |
| Key storage | `filesDir/adblib/priv.key` + `pub.key` |

## File yang JANGAN dihapus/ditimpa

- `app/src/main/java/com/erix/ptp/ptp/AdbLibUsbTransport.kt` ← **inti**
- `app/src/main/java/com/erix/ptp/ptp/AdbUsbTransport.kt` ← wrapper
- `app/src/main/java/com/erix/ptp/adb/AdbDeviceWatcher.kt` ← watcher USB

## File yang sudah dinonaktifkan (jangan diaktifkan lagi)

- `app/src/main/java/com/erix/ptp/ptp/PtpUsbTransport.kt.bak` ← PTP lama (limit 4GB)
- Dadb (`dev.mobile:dadb`) ← sudah dihapus dari dependencies

## Kalau mau eksperimen

1. **Buat file baru** (`*_v2.kt`), jangan edit file di atas.
2. **Backup manual** ke `~/erix-gh/.working-backup/`.
3. **Jalankan CI** sebelum merge ke main.
4. **Rollback:** lihat folder `.working-backup/20261023_235404/`.

## Bukti runtime (log)

```
USB Dicolok: /dev/bus/usb/001/003
Izin USB diberikan. Delay 1s...
Membuka koneksi ADB...
Membuat UsbSocket & AdbConnection...
Generate ADB key pair baru (RSA 2048)...
✓ ADB key disimpan: /data/user/0/com.erix.ptp/files/adblib/priv.key
Menunggu otorisasi ADB / CNXN...
[D popup "Allow USB debugging" muncul]
✓ AdbConnection tersambung
✓ ADB TERHUBUNG (/dev/bus/usb/001/003)
```
