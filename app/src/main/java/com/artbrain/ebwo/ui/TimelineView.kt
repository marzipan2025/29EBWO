package com.artbrain.ebwo.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 쪽을 네모로 늘어놓은 띠. 22SUTO-A 의 `MadeStrip` 을 옮겼다.
 *
 * 칸 하나가 한 쪽이다. 쪽이 많아 한 줄에 못 놓으면 여러 쪽을 한 칸에 묶고,
 * 칸을 누르면 그 칸이 맡은 첫 쪽으로 간다 — 어림자리로 옮기는 것이다.
 *
 * **띠는 늘 폭을 100% 쓴다.** 쪽이 적어 정사각형으로는 자리가 남으면 칸을
 * 길이 비례로 나눠 넓힌다 — 칸이 정사각형이 아니어도 좋다. 구석에만 짧게
 * 붙은 띠는 게이지로 읽히지 않고, 칸이 좁으면 누르기도 어렵다.
 *
 * **쓸어 옮기기는 두지 않았다.** e-ink 에서 손가락을 따라 잇달아 고쳐
 * 그리면 잔상만 남는다. 누르는 것만 받는다.
 */
class TimelineView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 칸의 최소 너비. 이보다 좁아지면 여러 쪽을 한 칸에 묶는다. */
    private val cellMin = Ink.dp(context, 5f).roundToInt().coerceAtLeast(3)

    /** 칸 높이의 한도. 칸이 넓어져도 띠가 뚱뚱해지지는 않게. */
    private val heightMax = Ink.dp(context, 12f).roundToInt()

    private val gap = Ink.dp(context, 1.5f).roundToInt().coerceAtLeast(1)

    private val done = Halftone.solid()                       // 지나온 쪽
    private val todo = Halftone.paint(Halftone.Tone.PALE)     // 아직 안 본 쪽
    private val now = Halftone.solid()                        // 지금 쪽
    private val r = RectF()

    private var count = 0
    private var current = 0

    /** 칸을 누르면 그 칸의 첫 쪽 번호가 온다. */
    var onSeek: ((page: Int) -> Unit)? = null

    fun set(count: Int, current: Int) {
        this.count = count
        this.current = current
        invalidate()
    }

    /** 놓을 칸 수 — 폭이 좁아 다 못 놓으면 쪽을 묶는다. */
    private fun cells(): Int {
        if (count <= 0 || width <= 0) return 0
        val maxCells = floor((width + gap).toDouble() / (cellMin + gap)).toInt().coerceAtLeast(1)
        return if (count < maxCells) count else maxCells
    }

    /**
     * [k] 번 칸의 왼쪽·오른쪽 자리.
     *
     * 폭을 [n] 로 비례해 나눈 경계를 정수로 맞춘다 — 칸마다 같은 너비를
     * 쓰고 남는 픽셀을 버리면 띠가 폭을 다 못 채운다. 경계를 먼저 정하면
     * 남는 픽셀이 칸들에 저절로 흩어지고 오른쪽 끝이 정확히 맞는다.
     */
    private fun bounds(k: Int, n: Int): Pair<Float, Float> {
        val step = width.toDouble() / n
        val left = (k * step).roundToInt()
        val right = ((k + 1) * step).roundToInt() - if (k < n - 1) gap else 0
        return left.toFloat() to right.coerceAtLeast(left + 1).toFloat()
    }

    override fun onDraw(canvas: Canvas) {
        val n = cells()
        if (n <= 0) return

        val (l0, r0) = bounds(0, n)
        val cellH = (r0 - l0).roundToInt().coerceAtMost(heightMax).coerceAtLeast(cellMin)
        val top = (height - cellH) / 2f

        // 지금 쪽이 몇 번째 칸에 드는가
        val nowCell = (current.toLong() * n / count).toInt().coerceIn(0, n - 1)

        for (k in 0 until n) {
            val (left, right) = bounds(k, n)
            r.set(left, top, right, top + cellH)
            when {
                k == nowCell -> canvas.drawRect(r, now)
                k < nowCell -> canvas.drawRect(r, done)
                else -> canvas.drawRect(r, todo)
            }
        }

        // 지금 칸 아래에 턱을 하나 둔다 — 같은 검정 속에서도 눈에 띄게.
        val (left, right) = bounds(nowCell, n)
        r.set(left, top + cellH + gap, right, top + cellH + gap * 2f)
        canvas.drawRect(r, now)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_UP) {
            return event.actionMasked == MotionEvent.ACTION_DOWN && count > 0
        }
        val n = cells()
        if (n <= 0) return false
        val k = floor(event.x * n / width).toInt().coerceIn(0, n - 1)
        val page = (k.toLong() * count / n).toInt().coerceIn(0, count - 1)
        onSeek?.invoke(page)
        return true
    }
}
