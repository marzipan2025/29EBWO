package com.artbrain.ebwo

import android.app.Application
import android.graphics.Typeface
import com.artbrain.ebwo.ui.Fonts

class App : Application() {

    /**
     * 본문 글꼴 — JTBC 명조.
     *
     * 에이투지체(a2z_semibold.ttf)도 res/font 에 그대로 두었다. 바꿔 보려면
     * 아래 한 줄만 고치면 된다. 두 글꼴 모두 저장소에는 넣지 않는다.
     *
     * APK 에 넣어 두었으므로 기기 파일에 기대지 않는다.
     */
    val bodyFont: Typeface by lazy { Fonts.of(this, Fonts.BODY) }
}
