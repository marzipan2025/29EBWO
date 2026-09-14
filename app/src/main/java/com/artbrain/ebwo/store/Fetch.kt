package com.artbrain.ebwo.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.artbrain.ebwo.drive.DriveApi
import com.artbrain.ebwo.text.Convert
import com.artbrain.ebwo.text.Epub
import com.artbrain.ebwo.ui.Ink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 문서 하나를 받아 기기에 앉힌다. 목록과 리더가 같은 길을 쓴다.
 *
 * 구글 문서는 글로 받아 그대로 쓴다. epub 은 파일을 받아 **글과 사진으로
 * 풀어 둔다** — 읽을 때마다 압축을 풀지 않도록, 그리고 리더가 구글 문서와
 * 똑같이 글 한 벌만 보면 되도록.
 *
 * 사진은 본문 속 한 줄 `￼0001.png` 로 적는다([Epub.IMAGE_MARK]). 한 문단이라
 * 문장 가르기를 거쳐도 제 쪽 하나를 차지한다.
 */
object Fetch {

    /**
     * 받아서 저장하고 본문을 돌려준다. [onProgress] 에 퍼센트를 알린다.
     *
     * epub 은 받기가 9할, 사진 바꾸기가 1할이다. 구글 문서는 크기를 미리 알 수
     * 없어 끝날 때 100 만 알린다.
     */
    suspend fun body(
        ctx: Context, store: DocStore, token: String, doc: Doc,
        onProgress: (Int) -> Unit = {},
    ): String {
        val api = DriveApi(token)
        if (doc.mimeType == Doc.GOOGLE_DOC) return api.exportText(doc.id).also {
            store.writeBody(doc.id, it)
            onProgress(100)
        }
        if (!doc.isEpub) return plain(ctx, store, api, doc, onProgress)

        val work = File(ctx.cacheDir, "epub").apply { mkdirs() }
        val file = File(work, "${doc.id}.epub")
        val staged = File(work, "${doc.id}-img")
        try {
            api.download(doc.id, file) { onProgress((it * DOWNLOAD_SHARE).toInt()) }
            return withContext(Dispatchers.IO) {
                staged.deleteRecursively(); staged.mkdirs()
                val text = unpack(ctx, file, staged) { f ->
                    ensureActive()
                    onProgress(DOWNLOAD_SHARE + (f * (100 - DOWNLOAD_SHARE)).toInt())
                }
                store.writeEpub(doc.id, text, staged)
                onProgress(100)
                text
            }
        } finally {
            file.delete()
            staged.deleteRecursively()
        }
    }

    /**
     * docx·txt·md·srt — 파일을 받아 기기에서 글로 푼다. 한글 인코딩은 [Convert.decode]
     * 가 알아맞힌다(EUC-KR·MS949 자막이 흔하다). 받기가 9할이다.
     */
    private suspend fun plain(
        ctx: Context, store: DocStore, api: DriveApi, doc: Doc, onProgress: (Int) -> Unit,
    ): String {
        val file = File(File(ctx.cacheDir, "file").apply { mkdirs() }, "${doc.id}.bin")
        try {
            api.download(doc.id, file) { onProgress((it * DOWNLOAD_SHARE).toInt()) }
            return withContext(Dispatchers.IO) {
                val text = try {
                    Convert.text(doc.mimeType, file)
                } catch (e: Convert.Unsupported) {
                    throw IllegalArgumentException(e.message)
                }
                if (text.isBlank()) throw IllegalArgumentException("읽을 글을 찾지 못했습니다.")
                store.writeBody(doc.id, text)
                onProgress(100)
                text
            }
        } finally {
            file.delete()
        }
    }

    private fun unpack(ctx: Context, file: File, outDir: File, onProgress: (Float) -> Unit): String = ZipFile(file).use { zip ->
        fun bytes(p: String) = zip.getEntry(p)?.let { e -> zip.getInputStream(e).use { it.readBytes() } }
        val blocks = Epub(::bytes).blocks()
        // 사진은 글 상자 폭의 정사각형에 차도록 — 리더의 상자와 같은 셈이다.
        val side = (ctx.resources.displayMetrics.widthPixels * Ink.BOX_FRACTION).toInt()
        val names = HashMap<String, String?>()   // zip 경로 → 저장한 이름 (못 쓰면 null)
        val sb = StringBuilder()
        for ((i, b) in blocks.withIndex()) {
            if (b is Epub.Image) onProgress(i.toFloat() / blocks.size)
            val line = when (b) {
                is Epub.Para -> b.text.replace(Epub.IMAGE_MARK.toString(), "")
                is Epub.Image -> names.getOrPut(b.path) {
                    val name = "%04d.png".format(names.size + 1)
                    val raw = bytes(b.path)
                    if (raw != null && saveGray(raw, side, File(outDir, name))) name else null
                }?.let { "${Epub.IMAGE_MARK}$it" }
            } ?: continue
            if (line.isBlank()) continue
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append(line)
        }
        if (sb.isEmpty()) throw IllegalArgumentException("epub 에서 읽을 글을 찾지 못했습니다.")
        sb.toString()
    }

    /**
     * 사진을 [side] 정사각형에 차게 맞추고 **16단계 회색**으로 저장한다.
     *
     * 점무늬(베이어·오차 확산)로 내면 기기의 화면 격자와 어긋나 물결무늬가
     * 선다. 기기가 16단계 회색을 제대로 내므로 그 단계에 맞춰 둔다. 작은
     * 사진도 비율대로 키워 상자에 채운다. 너무 작은 그림(여백용 점 따위)은
     * 버린다.
     */
    private fun saveGray(raw: ByteArray, side: Int, to: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        val w0 = bounds.outWidth
        val h0 = bounds.outHeight
        if (w0 <= 0 || h0 <= 0) return false
        if (w0 < TINY && h0 < TINY) return false

        // 필요한 크기의 두 배를 넘는 만큼만 줄여 읽는다 — 메모리를 아낀다.
        var sample = 1
        while (max(w0, h0) / (sample * 2) >= side * 2) sample *= 2
        val src = BitmapFactory.decodeByteArray(raw, 0, raw.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return false

        val scale = side.toFloat() / max(src.width, src.height)
        val w = (src.width * scale).roundToInt().coerceAtLeast(1)
        val h = (src.height * scale).roundToInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.WHITE)   // 투명한 곳은 종이색
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
            }
            drawBitmap(src, null, android.graphics.Rect(0, 0, w, h), paint)
        }
        src.recycle()

        val px = IntArray(w * h)
        out.getPixels(px, 0, w, 0, 0, w, h)
        for (i in px.indices) {
            val g = ((px[i] shr 16) and 0xFF)
            val q = ((g + 8) / 17) * 17
            px[i] = Color.rgb(q, q, q)
        }
        out.setPixels(px, 0, w, 0, 0, w, h)
        to.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        out.recycle()
        return true
    }

    /** 받기가 차지하는 퍼센트 — 나머지는 사진 바꾸기 */
    private const val DOWNLOAD_SHARE = 90

    /** 이보다 작은 그림은 가로·세로 모두 작으면 버린다 (px) */
    private const val TINY = 32
}
