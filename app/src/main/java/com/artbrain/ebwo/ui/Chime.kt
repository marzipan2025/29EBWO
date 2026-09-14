package com.artbrain.ebwo.ui

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.view.View
import android.widget.FrameLayout
import java.util.Calendar

/**
 * 정각과 30분에 **글자 뒤로 큰 시각을 3초 띄웠다가 걷으며 화면을 한 번 크게
 * 고친다.**
 *
 * e-ink 는 빠른 부분 갱신만 거듭하면 잔상이 쌓인다. 전체 갱신(GC16)은 화면이
 * 한 번 검게 깜빡이므로 아무 때나 할 수 없다 — 그래서 시각을 알리는 순간에
 * 묶는다. 깜빡임에 까닭이 생긴다.
 *
 * - **글자 아래에 깐다.** 루트의 맨 뒤(0번) 자식으로 넣는다. 누름을 먹지 않는다.
 * - **분이 바뀌는 신호(TIME_TICK)를 받는다.** Handler 로 다음 30분까지 재면
 *   기기가 잠들었다 깨거나 시계를 고쳤을 때 어긋난다. 신호는 분의 첫머리에
 *   오므로 따로 맞출 것이 없다.
 * - **화면에 보일 때만 산다**([resume]/[pause]). 떠 있는 동안 다른 화면으로
 *   가면 고치지 않고 걷는다 — 돌아왔을 때 남아 있지 않게.
 * - 걷을 때 [Eink.fullRefresh] 를 먼저 걸고 숨긴다. 숨기며 다시 그리는 한 번이
 *   전체 갱신이 된다.
 */
class Chime(private val activity: Activity, private val root: FrameLayout) {

    private val hand = Handler(Looper.getMainLooper())
    private val view = ChimeView(activity).apply { visibility = View.INVISIBLE }
    private var registered = false

    init {
        // 맨 뒤에 넣는다 — 글자와 조작판, 팝업이 모두 그 위에 온다.
        root.addView(view, 0, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = onTick()
    }

    private val finish = Runnable { hide(refresh = true) }

    fun resume() {
        if (registered) return
        activity.registerReceiver(receiver, IntentFilter(Intent.ACTION_TIME_TICK))
        registered = true
    }

    fun pause() {
        if (registered) { activity.unregisterReceiver(receiver); registered = false }
        hide(refresh = false)
    }

    private fun onTick() {
        val now = Calendar.getInstance()
        val m = now.get(Calendar.MINUTE)
        // 잠에서 늦게 깨어 받은 신호는 넘긴다 — 30분이 한참 지나 뜨면 헷갈린다.
        if (m % EVERY_MIN != 0 || now.get(Calendar.SECOND) > LATE_SEC) return
        if (view.visibility == View.VISIBLE) return
        view.text = "%02d:%02d".format(now.get(Calendar.HOUR_OF_DAY), m)
        view.visibility = View.VISIBLE
        hand.removeCallbacks(finish)
        hand.postDelayed(finish, SHOW_MS)
    }

    private fun hide(refresh: Boolean) {
        hand.removeCallbacks(finish)
        if (view.visibility != View.VISIBLE) return
        if (refresh) Eink.fullRefresh(root)
        // GONE 이 아니라 INVISIBLE — 자리를 재지 않게. 목록은 칸 높이를
        // 레이아웃이 끝날 때마다 다시 센다.
        view.visibility = View.INVISIBLE
    }

    private class ChimeView(ctx: Context) : View(ctx) {
        var text: String = ""
            set(v) { field = v; invalidate() }

        private val paint = TextPaint().apply {
            isAntiAlias = true
            color = Ink.BLACK
            setAlpha((255 * CLOCK_ALPHA).toInt())
            textAlign = Paint.Align.CENTER
            typeface = Fonts.of(ctx, Fonts.UI)
            fontVariationSettings = Fonts.THIN
            textSize = Ink.dp(ctx, SIZE_DP)
        }
        private val r = Rect()

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(canvas: Canvas) {
            if (text.isEmpty()) return
            // 먹의 세로 가운데를 화면 가운데에 둔다.
            paint.getTextBounds(text, 0, text.length, r)
            canvas.drawText(text, width / 2f, height / 2f - (r.top + r.bottom) / 2f, paint)
        }
    }

    private companion object {
        /** 몇 분마다 — 정각과 30분 */
        const val EVERY_MIN = 30
        const val SHOW_MS = 3_000L
        /** 분이 바뀐 뒤 이만큼 넘어 받은 신호는 넘긴다 */
        const val LATE_SEC = 20

        /** Geist Mono Thin Italic. 고정폭이라 `00:00` 은 늘 585px 폭이다. */
        const val SIZE_DP = 120f
        const val CLOCK_ALPHA = 0.2f
    }
}
