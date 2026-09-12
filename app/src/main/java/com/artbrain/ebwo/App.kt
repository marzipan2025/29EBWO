package com.artbrain.ebwo

import android.app.Application
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat

class App : Application() {

    /** 본문 글꼴. APK 에 넣어 두었으므로 기기 파일에 기대지 않는다. */
    val bodyFont: Typeface by lazy {
        ResourcesCompat.getFont(this, R.font.jtbc_regular) ?: Typeface.DEFAULT
    }
}
