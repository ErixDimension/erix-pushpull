package com.erix.ptp.util

/**
 * PathUtil — utility path untuk konsistensi join/normalize/sanitasi.
 * Dipakai untuk path remote Linux (device B) maupun lokal Android.
 *
 * Semua operasi berbasis forward slash (/) karena ADB shell Linux.
 */
object PathUtil {

    /**
     * Join parent + child dengan separator tunggal.
     * Contoh:
     *   join("/sdcard", "Download")       → "/sdcard/Download"
     *   join("/sdcard/", "/Download/")    → "/sdcard/Download"
     *   join("/sdcard", "")               → "/sdcard"
     */
    fun join(parent: String, child: String): String {
        val p = normalize(parent)
        val c = child.trim('/', ' ')
        if (c.isEmpty()) return p
        return if (p == "/") "/$c" else "$p/$c"
    }

    /**
     * Normalize path: hapus trailing slash, collapse "//", resolve "/./".
     * Tidak resolve ".." (biar user sadar kalau naik level).
     * Contoh:
     *   normalize("/sdcard/")             → "/sdcard"
     *   normalize("/sdcard//Download//")  → "/sdcard/Download"
     *   normalize("")                     → "/"
     */
    fun normalize(path: String): String {
        var p = path.trim()
        if (p.isEmpty()) return "/"
        // collapse multiple slashes
        while (p.contains("//")) p = p.replace("//", "/")
        // remove "/./"
        p = p.replace("/./", "/")
        // remove trailing slash (kecuali root)
        if (p.length > 1 && p.endsWith("/")) p = p.dropLast(1)
        return if (p.isEmpty()) "/" else p
    }

    /**
     * Ambil nama file dari path.
     * Contoh:
     *   basename("/sdcard/Download/video.mp4") → "video.mp4"
     *   basename("/sdcard/Download/")          → "Download"
     *   basename("/")                          → "/"
     */
    fun basename(path: String): String {
        val p = normalize(path)
        if (p == "/") return "/"
        val idx = p.lastIndexOf('/')
        return if (idx < 0) p else p.substring(idx + 1).ifEmpty { "/" }
    }

    /**
     * Ambil parent directory dari path.
     * Contoh:
     *   parentOf("/sdcard/Download/video.mp4") → "/sdcard/Download"
     *   parentOf("/sdcard")                    → "/"
     */
    fun parentOf(path: String): String {
        val p = normalize(path)
        if (p == "/") return "/"
        val idx = p.lastIndexOf('/')
        return if (idx <= 0) "/" else p.substring(0, idx)
    }

    /**
     * Sanitasi nama file: buang karakter yang bermasalah di shell Android/Linux.
     * Karakter yang dibuang: / \ : * ? " < > | dan newline
     * Contoh:
     *   sanitizeFilename("a/b:c?.txt") → "abc.txt"
     *   sanitizeFilename("")           → "file"
     */
    fun sanitizeFilename(name: String): String {
        if (name.isBlank()) return "file"
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "").trim()
        return cleaned.ifEmpty { "file" }
    }

    /** Cek apakah path absolut (diawali /). */
    fun isAbsolute(path: String): Boolean = path.trim().startsWith("/")
}
