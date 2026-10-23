package com.erix.ptp.util

object PathUtil {

    /**
     * Bersihkan path dari escaping shell: `\ ` → ` `, `\(` → `(`, dll.
     * Juga normalize `/./`, `//`, trailing slash.
     */
    fun cleanPath(path: String): String {
        if (path.isBlank()) return "/"
        var p = path
        // Buang backslash escaping (shell artifact): `\ `, `\(`, `\)`, `\[`, `\]`, `\&`
        p = p.replace(Regex("\\\\(.)"), "$1")
        // Normalize
        p = normalize(p)
        return p
    }

    fun join(parent: String, child: String): String {
        val p = normalize(parent)
        val c = child.trim('/', ' ')
        if (c.isEmpty()) return p
        return if (p == "/") "/$c" else "$p/$c"
    }

    fun normalize(path: String): String {
        var p = path.trim()
        if (p.isEmpty()) return "/"
        while (p.contains("//")) p = p.replace("//", "/")
        p = p.replace("/./", "/")
        if (p.length > 1 && p.endsWith("/")) p = p.dropLast(1)
        return if (p.isEmpty()) "/" else p
    }

    fun basename(path: String): String {
        val p = normalize(path)
        if (p == "/") return "/"
        val idx = p.lastIndexOf('/')
        return if (idx < 0) p else p.substring(idx + 1).ifEmpty { "/" }
    }

    fun parentOf(path: String): String {
        val p = normalize(path)
        if (p == "/") return "/"
        val idx = p.lastIndexOf('/')
        return if (idx <= 0) "/" else p.substring(0, idx)
    }

    fun sanitizeFilename(name: String): String {
        if (name.isBlank()) return "file"
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "").trim()
        return cleaned.ifEmpty { "file" }
    }

    fun isAbsolute(path: String): Boolean = path.trim().startsWith("/")
}
