package com.erix.ptp.transfer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ResumeState(
    val action: String,          // "upload"
    val localPath: String,
    val fileName: String,
    val fileSize: Long,
    val chunkSize: Long,
    val totalChunks: Int,
    val uploadedChunks: Set<Int>,
    val destHandle: Int,
    val timestamp: Long
) {
    companion object {
        private fun dir(ctx: Context): File =
            File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "resume").also { it.mkdirs() }

        private fun file(ctx: Context, fileName: String): File =
            File(dir(ctx), "${sanitize(fileName)}.json")

        private fun sanitize(s: String) = s.replace(Regex("[^a-zA-Z0-9._-]"), "_")

        fun load(ctx: Context, fileName: String): ResumeState? {
            val f = file(ctx, fileName)
            if (!f.exists()) return null
            return try {
                val o = JSONObject(f.readText())
                val arr = o.getJSONArray("uploadedChunks")
                val set = mutableSetOf<Int>()
                for (i in 0 until arr.length()) set.add(arr.getInt(i))
                ResumeState(
                    action = o.getString("action"),
                    localPath = o.getString("localPath"),
                    fileName = o.getString("fileName"),
                    fileSize = o.getLong("fileSize"),
                    chunkSize = o.getLong("chunkSize"),
                    totalChunks = o.getInt("totalChunks"),
                    uploadedChunks = set,
                    destHandle = o.getInt("destHandle"),
                    timestamp = o.getLong("timestamp")
                )
            } catch (_: Exception) { null }
        }

        fun save(ctx: Context, s: ResumeState) {
            val o = JSONObject().apply {
                put("action", s.action)
                put("localPath", s.localPath)
                put("fileName", s.fileName)
                put("fileSize", s.fileSize)
                put("chunkSize", s.chunkSize)
                put("totalChunks", s.totalChunks)
                val arr = JSONArray()
                s.uploadedChunks.sorted().forEach { arr.put(it) }
                put("uploadedChunks", arr)
                put("destHandle", s.destHandle)
                put("timestamp", s.timestamp)
            }
            file(ctx, s.fileName).writeText(o.toString())
        }

        fun clear(ctx: Context, fileName: String) {
            val f = file(ctx, fileName)
            if (f.exists()) f.delete()
        }
    }
}
