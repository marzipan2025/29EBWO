package com.artbrain.ebwo.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.widget.TextView

/**
 * 누름 표시 — 기호 아래에 `░` 를 **한 글자** 겹친다.
 *
 * 면을 칠하거나 무늬를 깔지 않는다. 기호도 `░` 도 결국 같은 글꼴의 한 글자
 * 이므로, 같은 크기·같은 자리에 겹쳐 놓으면 글자 뒤에 성긴 점무늬가 깔린
 * 꼴이 된다. e-ink 에서 네모가 나타났다 사라지는 것보다 조용하다.
 *
 * [owner] 의 붓을 그대로 베껴 쓰므로 글꼴·크기·굵기가 저절로 맞는다.
 * 자리 계산도 TextView 가 가운데 정렬할 때 쓰는 것과 같은 셈이라
 * **글자 상자가 정확히 포개진다.**
 */
class Shade(private val owner: TextView) : Drawable() {

    private val paint = Paint()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        paint.set(owner.paint)
        paint.isAntiAlias = true
        paint.color = Ink.BLACK
        paint.alpha = 255

        val blocks = BLOCK.repeat(owner.text?.length ?: 0)
        if (blocks.isEmpty()) return
        val advance = paint.measureText(blocks)
        val fm = paint.fontMetrics
        val x = b.left + (b.width() - advance) / 2f
        val y = b.top + (b.height() - (fm.descent - fm.ascent)) / 2f - fm.ascent
        canvas.drawText(blocks, x, y, paint)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(cf: ColorFilter?) = Unit
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        private const val BLOCK = "░"

        /** 누를 때만 뒤에 깔리게 붙인다. */
        fun applyTo(v: TextView) {
            v.background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), Shade(v))
                addState(intArrayOf(), null)
            }
        }
    }
}
