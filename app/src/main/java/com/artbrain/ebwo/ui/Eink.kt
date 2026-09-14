package com.artbrain.ebwo.ui

import android.util.Log
import android.view.View
import java.lang.reflect.Method

/**
 * Onyx e-ink 화면을 한 번 크게(GC16 전체 갱신) 고친다.
 *
 * Onyx 프레임워크의 `View.applyGCOnce()` 를 부른 뒤 다시 그리면 그 한 번이
 * 전체 갱신이 된다. 기기 로그에서 평소 `waveform_mode=1 update_mode=0`(빠른
 * 부분 갱신)이 `waveform_mode=2 update_mode=1`(GC16 전체)로 바뀌는 것을 봤다.
 *
 * 공개 API 가 아니라 반사로 부른다. `ViewUpdateHelper.fullRefreshScreen`,
 * `View.invalidate(int)` 은 일반 앱에 막혀 있고(blacklist) 이것만 열려 있다.
 * Onyx 가 아닌 기기이거나 막히면 조용히 아무 일도 하지 않는다.
 */
object Eink {

    private val applyGCOnce: Method? by lazy {
        runCatching { View::class.java.getMethod("applyGCOnce") }
            .onFailure { Log.i("EBWO", "applyGCOnce 없음: $it") }
            .getOrNull()
    }

    /** 다음 그리기를 전체 갱신으로 하고, 창 전체를 다시 그리게 한다. */
    fun fullRefresh(view: View) {
        val top = view.rootView
        runCatching { applyGCOnce?.invoke(top) }
            .onFailure { Log.i("EBWO", "applyGCOnce 실패: $it") }
        top.invalidate()
    }
}
