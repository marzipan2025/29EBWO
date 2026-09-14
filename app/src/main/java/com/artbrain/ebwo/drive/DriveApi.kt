package com.artbrain.ebwo.drive

import com.artbrain.ebwo.store.Doc
import com.artbrain.ebwo.text.Convert
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 드라이브에서 구글 문서·epub·docx·txt·md·srt 목록과 본문을 받아 온다.
 *
 * 구글 API 클라이언트 라이브러리를 쓰지 않는다 — 덩치가 크고, 우리가 부르는
 * 것은 두 갈래뿐이다. 메모리가 넉넉하지 않은 기기라 OkHttp 로 곧장 부른다.
 *
 * 본문은 `files.export` 로 `text/plain` 을 받는다. 이러면 언제나 UTF-8 이라
 * 인코딩을 알아맞힐 일이 없다. (SAF 문서 선택창은 구글 문서를 PDF 로만
 * 내주지만, 그 제약은 SAF 것이고 API 에는 없다.)
 *
 * 그 밖의 파일(epub·docx·txt·md·srt)은 그대로 받아([download]) 기기에서 푼다
 * ([com.artbrain.ebwo.text.Convert] — 30EBSE 와 같은 파일).
 */
class DriveApi(private val token: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * 휴지통에 없는, 읽을 수 있는 파일 전부. 최근 고친 것이 앞에 온다.
     *
     * md·srt 는 드라이브가 종류를 모르고 `application/octet-stream` 으로 두는 일이
     * 흔하다. 그래서 그 종류도 함께 묻고 **확장자로 걸러** 읽을 수 있는 것만 남긴다
     * ([Convert.kindOf]).
     *
     * **곁다리 파일은 뺀다**([isClutter]). 폰트·앱을 풀어 올린 폴더에 딸려 온 readme·
     * license·changelog 따위가 드라이브에 수백 개 있다(실측: 읽을 것 301개 중 130개 남짓).
     * 드라이브의 파일은 건드리지 않고 목록에만 올리지 않는다.
     */
    suspend fun listDocs(): List<Doc> = withContext(Dispatchers.IO) {
        val out = ArrayList<Doc>()
        var pageToken: String? = null
        do {
            val url = StringBuilder(FILES)
                .append("?q=").append(enc("(${MIMES.joinToString(" or ") { "mimeType='$it'" }}) and trashed=false"))
                .append("&orderBy=").append(enc("modifiedTime desc"))
                .append("&pageSize=100")
                .append("&fields=").append(enc("nextPageToken,files(id,name,modifiedTime,mimeType)"))
            if (pageToken != null) url.append("&pageToken=").append(enc(pageToken))

            val body = get(url.toString())
            val o = JSONObject(body)
            val files = o.optJSONArray("files")
            if (files != null) for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                val mime = f.optString("mimeType", Doc.GOOGLE_DOC)
                val name = f.optString("name", "(제목 없음)")
                // 구글 문서는 이름 그대로. 파일은 무엇으로 풀지 정하고 확장자를 뗀다.
                val kind = if (mime == Doc.GOOGLE_DOC) mime else Convert.kindOf(mime, name)
                if (kind == null || (mime != Doc.GOOGLE_DOC && isClutter(name))) continue
                val title = if (mime == Doc.GOOGLE_DOC) name else Convert.title(name)
                out += Doc(f.getString("id"), title, f.optString("modifiedTime"), mimeType = kind)
            }
            pageToken = o.optString("nextPageToken").ifEmpty { null }
        } while (pageToken != null)
        out
    }

    /** 문서 본문을 글로 받는다. */
    suspend fun exportText(id: String): String = withContext(Dispatchers.IO) {
        get("$FILES/$id/export?mimeType=" + enc("text/plain"))
    }

    /**
     * 파일을 그대로 [to] 에 받는다. 메모리에 올리지 않고 흘려 쓴다 — 사진이 든
     * epub 은 백 MB 를 넘기도 하는데 기기 메모리는 1.8GB 뿐이다.
     *
     * [onProgress] 에 받은 몫(0~1)을 알린다. 코루틴을 취소하면 다음 토막에서
     * 멈춘다 — 막혀 있는 읽기 자체를 끊지는 못하므로 토막을 작게 둔다.
     */
    suspend fun download(id: String, to: File, onProgress: (Float) -> Unit = {}) = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$FILES/$id?alt=media")
            .header("Authorization", "Bearer $token")
            .build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IOException(explain(res.code, res.body?.string().orEmpty()))
            val body = res.body ?: throw IOException("빈 응답입니다.")
            // 큰 파일은 크기를 헤더에 싣지 않고 흘려 보내기도 한다. 그러면 따로 묻는다.
            val total = body.contentLength().takeIf { it > 0 }
                ?: runCatching { JSONObject(get("$FILES/$id?fields=size")).optLong("size", -1L) }.getOrDefault(-1L)
            val buf = ByteArray(64 * 1024)
            var done = 0L
            body.byteStream().use { input ->
                to.outputStream().use { out ->
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        }
    }

    private fun get(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()
        http.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IOException(explain(res.code, body))
            return body
        }
    }

    /**
     * 실패한 까닭을 사람 말로 옮긴다. 인증 설정이 덜 된 것과 망이 끊긴 것을
     * 가려내지 못하면 어디를 고쳐야 할지 알 수 없다.
     */
    private fun explain(code: Int, body: String): String {
        val detail = runCatching {
            JSONObject(body).getJSONObject("error").optString("message")
        }.getOrNull().orEmpty()
        return when (code) {
            401 -> "인증이 만료되었습니다. 다시 로그인해 주세요. ($detail)"
            403 -> "권한이 없습니다. Drive API 사용 설정과 스코프를 확인하세요. ($detail)"
            404 -> "문서를 찾을 수 없습니다. ($detail)"
            else -> "드라이브 오류 $code ${detail.ifEmpty { body.take(200) }}"
        }
    }

    /**
     * 읽을 글이 아닌 파일인가.
     *
     * - 확장자가 epub·pdf·docx·txt·md·srt 가 아니면 뺀다 — 드라이브는 `.log` 도
     *   text/plain 으로 둔다.
     * - 소프트웨어·폰트 묶음에 딸려 오는 이름(readme, license, changelog, OFL, FONTLOG …)과
     *   폰트 만들기 도구의 설정 파일(maker, count, metrics …)을 뺀다.
     * - 압축을 풀며 이름이 깨진 것(`¼³¸í¼­` — '설명서' 의 CP949 를 Latin-1 로 읽은 것)을 뺀다.
     */
    private fun isClutter(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext !in READABLE_EXT) return true
        val base = name.substringBeforeLast('.').lowercase().trim()
        if (CLUTTER_PREFIX.any { base.startsWith(it) }) return true
        if (base in CLUTTER_NAME || CLUTTER_PATTERN.matches(base)) return true
        if ("open font license" in base || base.endsWith("-license")) return true
        return base.any { it in '\u0080'..'\u00FF' } && base.none { it in '\uAC00'..'\uD7A3' }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val FILES = "https://www.googleapis.com/drive/v3/files"
        /** 목록에 묻는 종류. octet-stream 은 확장자로 한 번 더 거른다. */
        private val MIMES = listOf(
            Doc.GOOGLE_DOC, Convert.EPUB, Convert.PDF, Convert.DOCX, Convert.TEXT, Convert.MD, "text/x-markdown",
            Convert.SRT, "text/srt", "application/octet-stream",
        )

        private val READABLE_EXT = setOf("epub", "pdf", "docx", "txt", "md", "markdown", "srt")
        private val CLUTTER_PREFIX = listOf(
            "readme", "read me", "license", "licence", "copying", "changelog", "change log",
            "authors", "contributors", "contributing", "fontlog", "ofl", "notice", "install notes",
        )
        private val CLUTTER_NAME = setOf(
            "changes", "history", "version", "credits", "maker", "count", "metrics", "hinting",
            "groups", "neighbors", "generator_config", "base_filter", "base_avoid_tag",
        )
        /** k1·k2… 같은 폰트 커닝 표, errors·errors (1) 같은 도구 기록 */
        private val CLUTTER_PATTERN = Regex("""k\d+|errors( \(\d+\))?""")

        const val SCOPE_DRIVE_READONLY = "https://www.googleapis.com/auth/drive.readonly"
    }
}
