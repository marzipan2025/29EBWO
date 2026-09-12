package com.artbrain.ebwo.ui

import android.content.Context
import android.util.TypedValue

/**
 * e-ink 화면의 눈금과 색.
 *
 * 색은 검정과 흰색 둘뿐이다. 중간 밝기가 필요한 자리는 [Halftone] 의 점무늬로
 * 낸다 — 회색으로 칠하면 e-ink 가 스스로 디더링하면서 얼룩이 남는다.
 */
object Ink {
    const val BLACK = 0xFF000000.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()

    /** 본문 글자 크기. 시스템 글꼴 배율을 타지 않도록 sp 가 아니라 dp 다. */
    const val TEXT_DP = 18f

    /** 본문 행간 곱 */
    const val LINE_SPACING = 1.5f

    /**
     * 글이 놓이는 상자 — 화면 가운데 60%.
     *
     * 가로 60% 는 한 줄을 짧게 잡아 눈이 줄을 되찾기 쉽게 하고, 세로 60% 는
     * 한 쪽에 들어갈 줄 수의 한도가 된다. 여기 들어가지 않는 문장은 쪼갠다 —
     * 글자 크기를 줄이지는 않는다.
     */
    const val BOX_FRACTION = 0.6f

    /** 손가락이 닿는 자리의 최소 크기. e-ink 터치는 정밀하지 않다. */
    const val TOUCH_DP = 56f

    fun dp(ctx: Context, v: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics
    )
}
