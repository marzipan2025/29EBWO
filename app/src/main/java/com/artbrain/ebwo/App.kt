package com.artbrain.ebwo

import android.app.Application
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat

class App : Application() {

    /**
     * 본문 글꼴 — 에이투지체 SemiBold. 22SUTO-A 와 같은 것을 쓴다.
     *
     * 한글 11,172자를 모두 덮고 굵기가 한 벌뿐이다. 그래서 본문 어디에도
     * 굵기를 주지 않아야 이 한 벌이 그대로 나온다.
     *
     * APK 에 넣어 두었으므로 기기 파일에 기대지 않는다.
     */
    val bodyFont: Typeface by lazy {
        ResourcesCompat.getFont(this, R.font.a2z_semibold) ?: Typeface.DEFAULT
    }
}
