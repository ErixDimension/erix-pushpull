package com.erix.twrp.transfer
import android.content.Context
import org.json.JSONObject
import java.io.File
class ResumeManager(context: Context) {
    private val dir = File(context.filesDir, "resume").apply { mkdirs() }
    data class Checkpoint(val version: Int, val action: String, val serial: String, val source: String, val destination: String, val totalSize: Long, val completedBytes: Long, val engine: String?, val chunkSize: Long?, val partPath: String?, val finalPath: String?, val transferId: String?, val sourceIdentity: Identity?, val destinationIdentity: Identity?)
    fun save(action: String, serial: String, src: String, dst: String, totalSize: Long, committed: Long, srcId: Identity, dstId: Identity, partPath: String, finalPath: String, transferId: String, chunkSize: Long) {
        val obj = JSONObject().apply { put("version", 2); put("action", action); put("serial", sanitize(serial)); put("source", src); put("destination", dst); put("total_size", totalSize); put("completed_bytes", committed); put("engine", "chunk"); put("chunk_size", chunkSize); put("part_path", partPath); put("final_path", finalPath); put("transfer_id", transferId); put("source_identity", srcId.toJson()); put("destination_identity", dstId.toJson()); put("updated", System.currentTimeMillis() / 1000.0) }
        val file = File(dir, "${action}_${sanitize(serial)}.json"); val tmp = File(dir, file.name + ".tmp"); tmp.writeText(obj.toString()); if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    }
    fun load(action: String, serial: String): Checkpoint? {
        val f = File(dir, "${action}_${sanitize(serial)}.json"); if (!f.isFile) return null
        return try { val o = JSONObject(f.readText()); Checkpoint(o.getInt("version"), o.getString("action"), o.getString("serial"), o.getString("source"), o.getString("destination"), o.getLong("total_size"), o.getLong("completed_bytes"), if (o.has("engine") && !o.isNull("engine")) o.getString("engine") else null, if (o.has("chunk_size") && !o.isNull("chunk_size")) o.getLong("chunk_size") else null, if (o.has("part_path") && !o.isNull("part_path")) o.getString("part_path") else null, if (o.has("final_path") && !o.isNull("final_path")) o.getString("final_path") else null, if (o.has("transfer_id") && !o.isNull("transfer_id")) o.getString("transfer_id") else null, if (o.has("source_identity") && !o.isNull("source_identity")) Identity.fromJson(o.getJSONObject("source_identity")) else null, if (o.has("destination_identity") && !o.isNull("destination_identity")) Identity.fromJson(o.getJSONObject("destination_identity")) else null) } catch (_: Exception) { null }
    }
    fun clear(action: String, serial: String) { File(dir, "${action}_${sanitize(serial)}.json").delete() }
    private fun sanitize(s: String) = s.replace("/", "_").replace(":", "_").replace(" ", "_")
}
