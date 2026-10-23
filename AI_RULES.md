# 🏛️ AI RULES — WAJIB DIBACA SEBELUM UBAH APA PUN

> Kontrak permanen antara user dan AI.
> Setiap sesi AI baru WAJIB baca file ini dulu.
> Command cepat: `ai-context` (dari Termux).

---

## ⛔ ATURAN MUTLAK

### 1. NO PATCHING — REWRITE, JANGAN TAMBAL

**DILARANG:**
- `sed -i` untuk ubah file Kotlin
- `awk` / `perl` untuk sisip method
- `cat >>` untuk append ke file yang ada
- Brace counting untuk cari lokasi sisip
- "Tambah method baru" dengan menempel di akhir class

**WAJIB:**
- Baca file sumber penuh dulu
- Rewrite penuh dengan `cat > file << 'EOF'` (full content)
- Hapus dan ganti — bukan tambah dan tambal
- Kalau perlu method baru: tulis ulang seluruh file

**Alasan:** patch tambal menyebabkan brace balance rusak, method di class yang salah, duplikasi tidak terdeteksi, kode tidak konsisten antar-versi.

---

### 2. BACA SUMBER DULU

**DILARANG:**
- Patch file yang belum di-`cat` penuh
- Mengasumsikan struktur file
- "Sepertinya begini"
- Coba-coba patch lalu fix

**WAJIB:**
- `cat FILE` → baca 100% sebelum ubah
- Kalau file > 200 baris, baca per blok (grep class/method dulu)
- Konfirmasi dengan user kalau struktur tidak jelas
- Tulis ulang file dengan struktur yang benar sejak awal

---

### 3. BACKUP SEBELUM UBAH

Setiap patch WAJIB diawali:

    BK=".backup/$(date +%Y%m%d_%H%M%S)_<nama_patch>"
    mkdir -p "$BK"
    cp <file-yang-akan-diubah> "$BK/"

---

### 4. MARKER HEADER DI FILE KUNCI

3 file yang JANGAN PERNAH di-touch tanpa rewrite penuh:
- app/src/main/java/com/erix/ptp/ptp/AdbLibUsbTransport.kt
- app/src/main/java/com/erix/ptp/ptp/AdbUsbTransport.kt
- app/src/main/java/com/erix/ptp/adb/AdbDeviceWatcher.kt

Semua punya header `⚠️ DO NOT DELETE / DO NOT OVERWRITE`.

---

### 5. AUTO-VERIFY SETELAH PATCH

Setiap patch yang berhasil push WAJIB disertai:
1. Cek brace balance semua file Kotlin
2. Cek marker utuh
3. Cek method tidak duplikat
4. Cek import tidak dobel
5. Auto-download APK kalau CI hijau

---

### 6. AUTO-DOWNLOAD SETELAH CI HIJAU

Setelah `gh run watch` sukses:

    dlapk

Tidak perlu user minta. Default = auto.

---

## ✅ CHECKLIST AI (setiap mulai sesi)

1. [ ] Baca AI_RULES.md (file ini)
2. [ ] Baca ARCHITECTURE.md
3. [ ] `git log --oneline -10`
4. [ ] Konfirmasi dengan user apa yang mau diubah
5. [ ] Baca file sumber penuh sebelum rewrite
6. [ ] Backup
7. [ ] Rewrite penuh (bukan patch)
8. [ ] Verify (brace, marker, duplikasi)
9. [ ] Push + watch + dlapk

---

## 🎯 SCOPE PROJECT

Nama: Terang Bulan (package `com.erix.ptp`)
Tujuan: Transfer file via ADB USB (bebas limit 4GB), pengganti PTP
Status: Browser browsing bekerja. Transfer menyusul di Stage 2c.

Yang SUDAH bekerja:
- ADB USB connect dengan popup RSA (AdbLib)
- Browser dual-tab (LOCAL / REMOTE)
- List directory remote via `ls -la`
- Back navigation (naik folder)
- State persistence (tab + path)

Yang BELUM:
- Transfer push/pull single file (Stage 2c)
- Transfer multi-file (Stage 2d)
- Chunked resume (Stage 2d)

---

## 🚨 KALAU RAGU

Jangan patch. Tanya user:
> "Saya perlu baca file X penuh dulu sebelum rewrite. Boleh saya dump?"

Lebih baik delay 1 menit daripada rusak kode yang sudah stabil.

---

**Versi:** 1.0
**Dibuat:** 2026-10-24
