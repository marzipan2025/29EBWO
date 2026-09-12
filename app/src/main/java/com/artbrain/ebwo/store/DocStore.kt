package com.artbrain.ebwo.store

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 목록에 뜨는 문서 한 칸 */
data class Doc(
    val id: String,
    val name: String,
    val modifiedTime: String,
    /** 본문을 내려받아 뒀나 */
    val cached: Boolean = false,
)

/**
 * 문서와 읽던 자리를 기기에 둔다.
 *
 * 앱 전용 폴더에만 쓴다 — 권한이 필요 없고, 앱을 지우면 함께 사라진다.
 *
 *   files/index.json     구글에서 받아 온 문서 목록
 *   files/pos.json       문서마다 읽던 쪽
 *   files/docs/<id>.txt  본문
 */
class DocStore(ctx: Context) {

    private val root: File = ctx.filesDir
    private val docsDir = File(root, "docs").apply { mkdirs() }
    private val indexFile = File(root, "index.json")
    private val posFile = File(root, "pos.json")

    // ── 목록 ──────────────────────────────────────────────

    fun loadIndex(): List<Doc> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(indexFile.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val id = o.getString("id")
                Doc(id, o.getString("name"), o.optString("modifiedTime"), bodyFile(id).exists())
            }
        }.getOrDefault(emptyList())
    }

    fun saveIndex(docs: List<Doc>) {
        val arr = JSONArray()
        for (d in docs) arr.put(JSONObject().apply {
            put("id", d.id); put("name", d.name); put("modifiedTime", d.modifiedTime)
        })
        indexFile.writeText(arr.toString())
    }

    // ── 본문 ──────────────────────────────────────────────

    private fun bodyFile(id: String) = File(docsDir, "${safe(id)}.txt")

    fun hasBody(id: String) = bodyFile(id).exists()

    fun readBody(id: String): String? =
        bodyFile(id).takeIf { it.exists() }?.runCatching { readText() }?.getOrNull()

    fun writeBody(id: String, text: String) = bodyFile(id).writeText(text)

    /** 받아 둔 본문과 읽던 자리를 지운다. 목록에서는 남는다. */
    fun deleteBody(id: String) {
        bodyFile(id).delete()
        savePos(id, 0)
    }

    fun bodyBytes(id: String): Long = bodyFile(id).let { if (it.exists()) it.length() else 0L }

    // ── 읽던 자리 ─────────────────────────────────────────

    private fun positions(): JSONObject =
        if (posFile.exists()) runCatching { JSONObject(posFile.readText()) }.getOrDefault(JSONObject())
        else JSONObject()

    fun loadPos(id: String): Int = positions().optInt(id, 0)

    fun savePos(id: String, page: Int) {
        val o = positions()
        if (page <= 0) o.remove(id) else o.put(id, page)
        posFile.writeText(o.toString())
    }

    /** 파일 이름에 쓸 수 없는 글자를 막는다. 드라이브 id 는 안전하지만 만약을 위해. */
    private fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_.-]"), "_")
}
