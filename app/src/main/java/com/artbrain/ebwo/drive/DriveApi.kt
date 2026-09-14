package com.artbrain.ebwo.drive

import com.artbrain.ebwo.store.Doc
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
 * 드라이브에서 구글 문서·epub 목록과 본문을 받아 온다.
 *
 * 구글 API 클라이언트 라이브러리를 쓰지 않는다 — 덩치가 크고, 우리가 부르는
 * 것은 두 갈래뿐이다. 메모리가 넉넉하지 않은 기기라 OkHttp 로 곧장 부른다.
 *
 * 본문은 `files.export` 로 `text/plain` 을 받는다. 이러면 언제나 UTF-8 이라
 * 인코딩을 알아맞힐 일이 없다. (SAF 문서 선택창은 구글 문서를 PDF 로만
 * 내주지만, 그 제약은 SAF 것이고 API 에는 없다.)
 *
 * epub 은 변환할 것이 없으므로 파일을 그대로 받는다([download]).
 */
class DriveApi(private val token: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 휴지통에 없는 구글 문서와 epub 전부. 최근 고친 것이 앞에 온다. */
    suspend fun listDocs(): List<Doc> = withContext(Dispatchers.IO) {
        val out = ArrayList<Doc>()
        var pageToken: String? = null
        do {
            val url = StringBuilder(FILES)
                .append("?q=").append(enc("(mimeType='${Doc.GOOGLE_DOC}' or mimeType='${Doc.EPUB}') and trashed=false"))
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
                var name = f.optString("name", "(제목 없음)")
                // 확장자는 목록에서 뗀다 — 무엇인지는 글을 열면 안다.
                if (mime == Doc.EPUB) name = name.removeSuffix(".epub").removeSuffix(".EPUB")
                out += Doc(f.getString("id"), name, f.optString("modifiedTime"), mimeType = mime)
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

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")

    companion object {
        private const val FILES = "https://www.googleapis.com/drive/v3/files"
        const val SCOPE_DRIVE_READONLY = "https://www.googleapis.com/auth/drive.readonly"
    }
}
