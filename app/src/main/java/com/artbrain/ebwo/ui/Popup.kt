package com.artbrain.ebwo.ui

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 화면 한가운데 뜨는 알림. 흰 면에 검은 테두리만 두른 네모다.
 *
 * 아래쪽에 슬쩍 뜨는 토스트는 e-ink 에서 잘 안 보인다 — 화면이 늦게 갱신되는
 * 사이 이미 사라져 있기도 하다. 가운데에 테두리를 두르고 세워 두면 놓치지
 * 않는다. 되돌릴 일이 있을 때는 단추를 함께 단다.
 */
class Popup(private val root: FrameLayout) {

    private val ctx = root.context
    private val hand = Handler(Looper.getMainLooper())
    private val hide = Runnable { dismiss() }

    private var box: LinearLayout? = null

    /** 시간이 다 되어 저절로 닫힐 때 할 일 — 되돌리지 않은 것으로 친다. */
    private var onExpire: (() -> Unit)? = null

    fun show(
        msg: String,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
        onExpire: (() -> Unit)? = null,
        ms: Long = PLAIN_MS,
    ) {
        dismiss(runExpire = true)
        this.onExpire = onExpire

        val pad = Ink.dp(ctx, 20f).toInt()
        val v = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(Ink.dp(ctx, 1.5f).toInt(), Color.BLACK)
            }
            // 뒤쪽으로 누름이 새어 나가지 않게 한다.
            isClickable = true
            addView(TextView(ctx).apply {
                text = msg
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER
                typeface = Fonts.of(ctx, Fonts.BODY)
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            })
            if (actionLabel != null) addView(TextView(ctx).apply {
                text = actionLabel
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER
                // 단추 글자는 번호·화살표와 같은 결로 — 가는 이탤릭 Geist Mono.
                typeface = Fonts.of(ctx, Fonts.UI)
                fontVariationSettings = Fonts.THIN
                setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16f)
                setPadding(pad, pad, pad, 0)
                minHeight = Ink.dp(ctx, Ink.TOUCH_DP).toInt()
                Shade.applyTo(this)
                setOnClickListener {
                    // 되돌렸으면 만료 처리를 하지 않는다.
                    this@Popup.onExpire = null
                    dismiss()
                    onAction?.invoke()
                }
            })
        }
        root.addView(v, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER,
        ))
        box = v
        hand.removeCallbacks(hide)
        hand.postDelayed(hide, ms)
    }

    fun dismiss(runExpire: Boolean = true) {
        hand.removeCallbacks(hide)
        box?.let { (it.parent as? FrameLayout)?.removeView(it) }
        box = null
        if (runExpire) onExpire?.invoke()
        onExpire = null
    }

    fun visible(): Boolean = box != null

    companion object {
        const val PLAIN_MS = 2_500L
        const val UNDO_MS = 6_000L
    }
}
