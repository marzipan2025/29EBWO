package com.artbrain.ebwo.drive

import com.artbrain.ebwo.store.Doc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 드라이브에서 구글 문서 목록과 본문을 받아 온다.
 *
 * 구글 API 클라이언트 라이브러리를 쓰지 않는다 — 덩치가 크고, 우리가 부르는
 * 것은 두 갈래뿐이다. 메모리가 넉넉하지 않은 기기라 OkHttp 로 곧장 부른다.
 *
 * 본문은 `files.export` 로 `text/plain` 을 받는다. 이러면 언제나 UTF-8 이라
 * 인코딩을 알아맞힐 일이 없다. (SAF 문서 선택창은 구글 문서를 PDF 로만
 * 내주지만, 그 제약은 SAF 것이고 API 에는 없다.)
 */
class DriveApi(private val token: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 휴지통에 없는 구글 문서 전부. 최근 고친 것이 앞에 온다. */
    suspend fun listDocs(): List<Doc> = withContext(Dispatchers.IO) {
        val out = ArrayList<Doc>()
        var pageToken: String? = null
        do {
            val url = StringBuilder(FILES)
                .append("?q=").append(enc("mimeType='application/vnd.google-apps.document' and trashed=false"))
                .append("&orderBy=").append(enc("modifiedTime desc"))
                .append("&pageSize=100")
                .append("&fields=").append(enc("nextPageToken,files(id,name,modifiedTime)"))
            if (pageToken != null) url.append("&pageToken=").append(enc(pageToken))

            val body = get(url.toString())
            val o = JSONObject(body)
            val files = o.optJSONArray("files")
            if (files != null) for (i in 0 until files.length()) {
                val f = files.getJSONObject(i)
                out += Doc(f.getString("id"), f.optString("name", "(제목 없음)"), f.optString("modifiedTime"))
            }
            pageToken = o.optString("nextPageToken").ifEmpty { null }
        } while (pageToken != null)
        out
    }

    /** 문서 본문을 글로 받는다. */
    suspend fun exportText(id: String): String = withContext(Dispatchers.IO) {
        get("$FILES/$id/export?mimeType=" + enc("text/plain"))
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
