package com.artbrain.ebwo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.artbrain.ebwo.auth.DriveAuth
import com.artbrain.ebwo.drive.DriveApi
import com.artbrain.ebwo.store.DocStore
import com.artbrain.ebwo.ui.PageView
import com.artbrain.ebwo.ui.TimelineView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 한 쪽에 문장 하나를 보여 준다.
 *
 * **아래 절반을 누르면 다음 문장**, **위 절반을 누르면 조작판**이 나오고
 * 사라진다. 쓸어 넘기기는 두지 않았다 — e-ink 에서 손가락을 따라 고쳐
 * 그리면 잔상만 남는다.
 *
 * 조작판은 5초 동안 아무 일이 없으면 스스로 숨는다. 숨을 때 화면을 한 번
 * 크게 고쳐 그리므로, 너무 짧게 두면 깜빡임이 잦아진다.
 */
class ReaderActivity : Activity() {

    private val scope = MainScope()
    private val hand = Handler(Looper.getMainLooper())
    private lateinit var store: DocStore

    private lateinit var root: FrameLayout
    private lateinit var pageView: PageView
    private lateinit var ui: View
    private lateinit var progress: TextView
    private lateinit var timeline: TimelineView
    private lateinit var toast: TextView

    private lateinit var bars: WindowInsetsControllerCompat
    private var docId: String = ""
    private var docName: String = ""
    private var busy = false

    private val hideUi = Runnable { setUiVisible(false) }
    private val hideToast = Runnable { toast.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)
        store = DocStore(this)

        // 글만 남기려면 시스템 막대도 함께 물러나야 한다. 조작판과 같이 움직인다.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        bars = WindowInsetsControllerCompat(window, window.decorView).apply {
            // 쓸어올릴 때만 잠깐 나오게 둔다 — 누르는 것으로 나오면 쪽 넘김을 먹는다.
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        docId = intent.getStringExtra(EXTRA_ID).orEmpty()
        docName = intent.getStringExtra(EXTRA_NAME).orEmpty()

        root = findViewById(R.id.root)
        pageView = findViewById(R.id.page)
        ui = findViewById(R.id.ui)
        progress = findViewById(R.id.progress)
        timeline = findViewById(R.id.timeline)
        toast = findViewById(R.id.toast)

        pageView.setFont((application as App).bodyFont)
        pageView.onPaginated = { updateChrome() }

        findViewById<Button>(R.id.toList).setOnClickListener { finish() }
        findViewById<Button>(R.id.prev).setOnClickListener {
            if (pageView.prev()) afterTurn() else say("첫 문장입니다.")
            keepUiAwake()
        }
        findViewById<Button>(R.id.refresh).setOnClickListener { refresh(); keepUiAwake() }
        timeline.onSeek = { p -> pageView.page = p; afterTurn(); keepUiAwake() }

        // 판이 열려 있는 동안 판 자체를 누르는 것은 넘김으로 세지 않는다.
        ui.setOnClickListener { keepUiAwake() }

        root.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_UP) onTap(e.y)
            true
        }

        val body = store.readBody(docId)
        if (body == null) {
            say("받아 둔 글이 없습니다. 새로고침을 눌러 주세요.")
            setUiVisible(true)
        } else {
            pageView.setDocument(body, store.loadPos(docId))
            setUiVisible(false)
        }
    }

    override fun onPause() {
        super.onPause()
        if (pageView.pageCount > 0) store.savePos(docId, pageView.page)
    }

    override fun onDestroy() {
        hand.removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDestroy()
    }

    /** 위 절반 = 조작판 토글, 아래 절반 = 다음 문장 */
    private fun onTap(y: Float) {
        if (y < root.height / 2f) {
            setUiVisible(ui.visibility != View.VISIBLE)
        } else {
            if (pageView.next()) afterTurn() else say("마지막 문장입니다.")
        }
    }

    private fun afterTurn() {
        store.savePos(docId, pageView.page)
        updateChrome()
    }

    private fun updateChrome() {
        val n = pageView.pageCount
        progress.text = if (n == 0) docName else "${pageView.page + 1} / $n"
        timeline.set(n, pageView.page)
    }

    private fun setUiVisible(show: Boolean) {
        ui.visibility = if (show) View.VISIBLE else View.GONE
        val sysBars = WindowInsetsCompat.Type.systemBars()
        if (show) bars.show(sysBars) else bars.hide(sysBars)
        hand.removeCallbacks(hideUi)
        if (show) {
            updateChrome()
            hand.postDelayed(hideUi, UI_TIMEOUT_MS)
        }
    }

    /** 조작판을 만지는 동안에는 숨지 않게 시계를 되감는다. */
    private fun keepUiAwake() {
        if (ui.visibility != View.VISIBLE) return
        hand.removeCallbacks(hideUi)
        hand.postDelayed(hideUi, UI_TIMEOUT_MS)
    }

    /** 이 문서의 글을 구글에서 다시 받는다. 읽던 자리는 지킨다. */
    private fun refresh() {
        if (busy || docId.isEmpty()) return
        say("다시 받고 있습니다…")
        pending = { token ->
            scope.launch {
                busy = true
                try {
                    val text = DriveApi(token).exportText(docId)
                    store.writeBody(docId, text)
                    val keep = pageView.page
                    pageView.setDocument(text, keep)
                    say("다시 받았습니다.")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e.message?.contains("인증이 만료") == true) DriveAuth.forget()
                    say(e.message ?: "받지 못했습니다.")
                } finally {
                    busy = false
                }
            }
        }
        DriveAuth.request(this, onToken = { pending?.invoke(it) }, onError = { say(it) })
    }

    private var pending: ((String) -> Unit)? = null

    @Deprecated("프레임워크 Activity 를 쓰므로 이 갈래가 맞다")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (DriveAuth.onActivityResult(this, requestCode, data,
                onToken = { pending?.invoke(it) }, onError = { say(it) })) return
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun say(msg: String) {
        toast.text = msg
        toast.visibility = View.VISIBLE
        hand.removeCallbacks(hideToast)
        hand.postDelayed(hideToast, TOAST_MS)
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_NAME = "name"
        private const val UI_TIMEOUT_MS = 5_000L
        private const val TOAST_MS = 2_500L
    }
}
