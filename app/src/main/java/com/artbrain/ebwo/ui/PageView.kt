package com.artbrain.ebwo.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import com.artbrain.ebwo.text.PageBuilder
import com.artbrain.ebwo.text.Sentences
import com.artbrain.ebwo.text.WordWrap
import kotlin.math.floor

/**
 * 한 쪽에 문장 하나를 그린다.
 *
 * 글은 **화면 가운데 60% 상자** 안에만 놓이고, 그 안에서 가로·세로 모두
 * 가운데로 맞춘다. 상자에 들어가지 않는 문장은 [PageBuilder] 가 쪼갠다.
 *
 * 줄바꿈은 [WordWrap] 이 **띄어쓰기에서만** 한다. 안드로이드에 맡기면 한글이
 * 음절 단위로 끊기고, 그걸 막는 `LineBreakConfig` 는 API 33 부터다.
 *
 * 글자에는 앤티에일리어싱을 쓴다 — 212dpi 에서 계단이 보이면 읽기 힘들다.
 * 회색 무늬를 쓰는 [Halftone] 과는 반대로 가는 것이 맞다.
 */
class PageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = TextPaint().apply {
        isAntiAlias = true
        color = Ink.BLACK
        textSize = Ink.dp(context, Ink.TEXT_DP)
    }

    private var raw: String = ""
    private var pages: List<String> = emptyList()
    private var layout: StaticLayout? = null
    private var pendingRestore = 0

    /** 글 상자의 크기 — 화면 가운데 [Ink.BOX_FRACTION] 만큼 */
    private var boxW = 1
    private var boxH = 1

    /** 한 쪽에 들어가는 최대 줄 수 */
    var maxLines: Int = 1
        private set

    var page: Int = 0
        set(value) {
            val v = value.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
            if (v == field && layout != null) return
            field = v
            rebuildLayout()
            invalidate()
        }

    val pageCount: Int get() = pages.size

    /** 쪽이 새로 나뉘면 알린다 — 진행 표시와 타임라인을 다시 그려야 한다. */
    var onPaginated: ((count: Int) -> Unit)? = null

    fun setFont(tf: Typeface) {
        paint.typeface = tf
        repaginate()
    }

    /** 문서 본문을 앉힌다. [restore] 쪽부터 보여 준다. */
    fun setDocument(text: String, restore: Int) {
        raw = text
        pendingRestore = restore
        repaginate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        repaginate()
    }

    private fun lineHeight(): Float {
        val fm = paint.fontMetrics
        return (fm.descent - fm.ascent) * Ink.LINE_SPACING + spacingAdd
    }

    private val spacingAdd = Ink.dp(context, Ink.LINE_SPACING_ADD_DP)

    /** 띄어쓰기에서만 끊어 나눈 줄들 */
    private fun wrapLines(s: String): List<String> =
        WordWrap.wrap(s, boxW.toFloat()) { paint.measureText(it) }

    private fun repaginate() {
        if (width <= 0 || height <= 0) return

        boxW = (width * Ink.BOX_FRACTION).toInt().coerceAtLeast(1)
        boxH = (height * Ink.BOX_FRACTION).toInt().coerceAtLeast(1)
        // 상자 높이가 허락하는 줄 수와 [Ink.MAX_LINES] 가운데 작은 쪽.
        val roomy = floor(boxH / lineHeight()).toInt()
        maxLines = minOf(Ink.MAX_LINES, roomy).coerceAtLeast(1)

        val fits: (String) -> Boolean = { s ->
            s.isEmpty() || wrapLines(s).size <= maxLines
        }

        pages = if (raw.isBlank()) emptyList()
        else PageBuilder.build(Sentences.split(raw), fits)

        android.util.Log.i("EBWO", "box=${boxW}x${boxH} lineH=${"%.1f".format(lineHeight())} " +
            "maxLines=$maxLines 한줄글자=${(boxW / paint.measureText("가")).toInt()} " +
            "쪽최대글자=${(maxLines * boxW / paint.measureText("가")).toInt()} " +
            "원문=${raw.length}자 쪽=${pages.size}")

        page = pendingRestore.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
        rebuildLayout()
        onPaginated?.invoke(pages.size)
        invalidate()
    }

    private fun rebuildLayout() {
        val s = pages.getOrNull(page)
        if (s == null || width <= 0) { layout = null; return }
        // 우리가 끊은 줄을 그대로 그린다. 줄바꿈이 이미 박혀 있으므로
        // StaticLayout 이 따로 끊을 일이 없다.
        val wrapped = wrapLines(s).joinToString("\n")
        layout = StaticLayout.Builder
            .obtain(wrapped, 0, wrapped.length, paint, boxW)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(spacingAdd, Ink.LINE_SPACING)
            .setIncludePad(false)
            .build()
    }

    /** 지금 글줄 덩이의 위·아래 자리. 글이 없으면 null. */
    val textTop: Float?
        get() = layout?.let { (height - it.height) / 2f }

    val textBottom: Float?
        get() = layout?.let { (height - it.height) / 2f + it.height }

    /** [x],[y] 가 글이 놓인 네모 안인가 — 상자 폭과 글줄 높이로 잰다. */
    fun hitsText(x: Float, y: Float): Boolean {
        val t = textTop ?: return false
        val b = textBottom ?: return false
        val left = (width - boxW) / 2f
        return x >= left && x <= left + boxW && y >= t && y <= b
    }

    /** 다음 쪽으로. 마지막이면 false. */
    fun next(): Boolean {
        if (page >= pages.size - 1) return false
        page += 1
        return true
    }

    /** 앞 쪽으로. 처음이면 false. */
    fun prev(): Boolean {
        if (page <= 0) return false
        page -= 1
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Ink.WHITE)
        val l = layout ?: return
        canvas.save()
        canvas.translate((width - boxW) / 2f, (height - l.height) / 2f)
        l.draw(canvas)
        canvas.restore()
    }
}
