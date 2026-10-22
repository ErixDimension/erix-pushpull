package com.erix.twrp.transfer
import org.json.JSONObject
import java.io.File
data class Identity(val kind: String, val path: String, val size: Long?, val mtimeNs: Long?, val fingerprint: String?) {
    fun toJson() = JSONObject().apply { put("kind", kind); put("path", path); put("size", size ?: JSONObject.NULL); put("mtime_ns", mtimeNs ?: JSONObject.NULL); put("fingerprint", fingerprint ?: JSONObject.NULL) }
    companion object {
        fun fromJson(o: JSONObject) = Identity(o.getString("kind"), o.getString("path"), if (o.isNull("size")) null else o.getLong("size"), if (o.isNull("mtime_ns")) null else o.getLong("mtime_ns"), if (o.isNull("fingerprint")) null else o.getString("fingerprint"))
        fun fromLocal(path: String): Identity { val f = File(path); return Identity("local", try { f.canonicalPath } catch (_: Exception) { path }, if (f.exists()) f.length() else null, if (f.exists()) f.lastModified() * 1_000_000L else null, null) }
        fun fromRemote(path: String, size: Long? = null) = Identity("remote", path.replace(Regex("/+"), "/"), size, null, null)
        fun matches(a: Identity, b: Identity): Boolean { if (a.kind != b.kind || a.path != b.path) return false; if (a.size != null && b.size != null && a.size != b.size) return false; if (a.mtimeNs != null && b.mtimeNs != null && a.mtimeNs != b.mtimeNs) return false; return true }
    }
}
