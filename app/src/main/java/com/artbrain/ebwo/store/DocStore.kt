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
    private val trashDir = File(root, "trash")
    private val indexFile = File(root, "index.json")
    private val posFile = File(root, "pos.json")

    // ── 목록 ──────────────────────────────────────────────

    /**
     * 목록을 읽는다. **받아 둔 문서가 앞에 온다.**
     *
     * 순서를 따로 저장하지 않고 받아뒀는지 여부에서 매번 셈한다. 그래서 글을
     * 지우면 그 칸은 곧바로 제자리(안 받은 것들 사이)로 돌아간다. 보이던
     * 자리를 붙잡아 두려면 "지금 순서" 를 어딘가 들고 있어야 하는데, 그것이
     * 새로고침·쪽 이동·앱 재시작과 어긋나기 시작하면 고치기 어려운 버그가
     * 된다. 셈해서 만드는 순서에는 어긋날 여지가 없다.
     *
     * 같은 무리 안에서는 구글이 준 차례(최근 고친 순)를 지킨다 —
     * [sortedByDescending] 는 차례를 흩뜨리지 않는다.
     */
    fun loadIndex(): List<Doc> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(indexFile.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val id = o.getString("id")
                Doc(id, o.getString("name"), o.optString("modifiedTime"), bodyFile(id).exists())
            }.sortedByDescending { it.cached }
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
    private fun trashFile(id: String) = File(trashDir, "${safe(id)}.txt")

    fun hasBody(id: String) = bodyFile(id).exists()

    fun readBody(id: String): String? =
        bodyFile(id).takeIf { it.exists() }?.runCatching { readText() }?.getOrNull()

    fun writeBody(id: String, text: String) = bodyFile(id).writeText(text)

    /**
     * 받아 둔 본문을 **치운다**. 지우는 것이 아니라 [trashDir] 로 옮긴다.
     *
     * 되돌릴 수 있어야 하기 때문이다. 읽던 자리도 지우지 않고 남겨 둔다 —
     * 되돌리면 읽던 데서 이어야 한다. 정말 지우는 것은 [purge] 가 한다.
     */
    fun deleteBody(id: String): Boolean {
        val f = bodyFile(id)
        if (!f.exists()) return false
        trashDir.mkdirs()
        return f.renameTo(trashFile(id))
    }

    /** 치워 둔 것을 되돌린다. */
    fun restoreBody(id: String): Boolean {
        val t = trashFile(id)
        return t.exists() && t.renameTo(bodyFile(id))
    }

    /** 치워 둔 것을 정말 지운다. 되돌릴 기회가 지난 뒤에 부른다. */
    fun purge(id: String) {
        trashFile(id).delete()
        savePos(id, 0)
    }

    /** 남아 있는 치운 것들을 모두 지운다. 앱을 다시 켤 때 한 번 쓸어 낸다. */
    fun purgeAll() {
        trashDir.listFiles()?.forEach { f ->
            f.name.removeSuffix(".txt").let { savePos(it, 0) }
            f.delete()
        }
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
