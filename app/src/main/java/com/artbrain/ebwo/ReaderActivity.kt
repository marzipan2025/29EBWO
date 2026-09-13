package com.artbrain.ebwo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.util.TypedValue
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.artbrain.ebwo.auth.DriveAuth
import com.artbrain.ebwo.drive.DriveApi
import com.artbrain.ebwo.store.DocStore
import com.artbrain.ebwo.ui.Fonts
import com.artbrain.ebwo.ui.Glyph
import com.artbrain.ebwo.ui.Ink
import com.artbrain.ebwo.ui.PageView
import com.artbrain.ebwo.ui.TimelineView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 한 쪽에 문장 하나를 보여 준다.
 *
 * 화면을 셋으로 나눠 누른다. 쓸어 넘기기는 두지 않았다 — e-ink 에서 손가락을
 * 따라 고쳐 그리면 잔상만 남는다.
 *
 * ```
 * ┌─────────────────────────┐
 * │   위 30%  ·  조작판 토글   │
 * ├─────┬───────────────────┤
 * │ 20% │                   │
 * │ 이전 │   80%  ·  다음     │
 * │     │                   │
 * └─────┴───────────────────┘
 * ```
 *
 * 다음으로 가는 자리를 넓게 둔다. 읽는 동안 아홉 번은 다음이고 한 번이
 * 이전이라, 자주 쓰는 쪽이 넓어야 보지 않고도 누를 수 있다.
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
    private lateinit var number: TextView
    private lateinit var toList: TextView
    private lateinit var refreshBtn: TextView
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
        number = findViewById(R.id.number)
        toList = findViewById(R.id.toList)
        refreshBtn = findViewById(R.id.refresh)
        timeline = findViewById(R.id.timeline)

        // 번호는 Geist Mono 의 가는 이탤릭. 가변 폰트라 굵기 축을 100 으로 세운다.
        number.typeface = Fonts.of(this, Fonts.UI)
        number.fontVariationSettings = "'wght' 100"
        number.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)

        // 화살표도 번호와 같은 가는 이탤릭으로. 누르는 자리(박스)는 그대로
        // 두고 글자만 키운다 — 손가락이 닿는 넓이는 지키면서 눈에는 크게.
        val geistItalic = Fonts.of(this, Fonts.UI)
        for (b in listOf(toList, refreshBtn)) {
            b.typeface = geistItalic
            b.fontVariationSettings = "'wght' 100"
            b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, GLYPH_DP)
            b.includeFontPadding = false
            b.gravity = Gravity.CENTER
            b.post { Glyph.center(b) }
        }
        toast = findViewById(R.id.toast)

        pageView.setFont((application as App).bodyFont)
        pageView.onPaginated = { updateChrome() }

        toList.setOnClickListener { finish() }
        refreshBtn.setOnClickListener { refresh(); keepUiAwake() }
        timeline.onSeek = { p -> pageView.page = p; afterTurn(); keepUiAwake() }

        // 막대를 숨기려고 화면 끝까지 쓰게 해 두었으므로(setDecorFitsSystemWindows
        // = false), 조작판이 상태바 밑으로 들어간다. 막대가 나와 있는 동안에는
        // 그 높이만큼 밀어 준다 — 그러지 않으면 첫 줄인 진행 표시가 가려진다.
        // 자리를 화면 비율로 잡는다 — 번호는 위에서 15%, 타임라인은 92%.
        // 막대를 숨기려고 화면 끝까지 쓰게 해 두었으므로 인셋은 따로 안 민다.
        // 막대를 숨기려고 화면 끝까지 쓰므로, 막대가 나와 있는 동안 위쪽 단추가
        // 상태바에 잘린다. 그만큼 내려 준다.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            barTop = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            placeByRatio()
            insets
        }
        root.post { placeByRatio() }

        root.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_UP) onTap(e.x, e.y)
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

    /** 위 [TOP_ZONE] = 조작판 토글, 그 아래는 왼쪽 [LEFT_ZONE] 이 이전·나머지가 다음 */
    private fun onTap(x: Float, y: Float) {
        if (y < root.height * TOP_ZONE) {
            setUiVisible(!uiShown)
            return
        }
        if (x < root.width * LEFT_ZONE) {
            if (pageView.prev()) afterTurn() else say("첫 문장입니다.")
        } else {
            if (pageView.next()) afterTurn() else say("마지막 문장입니다.")
        }
    }

    private fun afterTurn() {
        store.savePos(docId, pageView.page)
        updateChrome()
    }

    private var barTop = 0


    /** 번호와 타임라인을 화면 비율 자리에, 위쪽 단추를 막대 아래에 놓는다. */
    private fun placeByRatio() {
        val h = root.height
        if (h <= 0) return
        // 단추는 번호와 세로 가운데를 맞춘다. 그 자리에서 위까지의 거리를
        // 그대로 좌우 벽과의 거리로도 쓴다 — 위·옆 간격이 같아 모서리에
        // 매달린 느낌이 사라진다.
        val boxH = toList.height.takeIf { it > 0 } ?: Ink.dp(this, 56f).toInt()
        val topM = ((h * NUMBER_Y).toInt() - boxH / 2).coerceAtLeast(barTop)
        for (b in listOf(toList, refreshBtn)) {
            (b.layoutParams as FrameLayout.LayoutParams).let {
                it.topMargin = topM
                it.marginStart = topM
                it.marginEnd = topM
                b.layoutParams = it
            }
        }
        (number.layoutParams as FrameLayout.LayoutParams).let {
            it.topMargin = (h * NUMBER_Y).toInt() - number.height / 2
            number.layoutParams = it
        }
        (timeline.layoutParams as FrameLayout.LayoutParams).let {
            it.width = (root.width * TIMELINE_W).toInt()
            it.topMargin = (h * TIMELINE_Y).toInt() - timeline.height / 2
            timeline.layoutParams = it
        }
    }

    private fun updateChrome() {
        val n = pageView.pageCount
        number.text = if (n == 0) "" else "${pageView.page + 1}"
        timeline.set(n, pageView.page)
        // 막대를 숨기려고 화면 끝까지 쓰므로, 막대가 나와 있는 동안 위쪽 단추가
        // 상태바에 잘린다. 그만큼 내려 준다.
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            barTop = insets.getInsets(WindowInsetsCompat.Type.systemBars()).top
            placeByRatio()
            insets
        }
        root.post { placeByRatio() }
    }

    /** 조작판은 두 아이콘과 타임라인이다. 번호는 여기 들지 않는다 — 늘 떠 있다. */
    private fun setUiVisible(show: Boolean) {
        val v = if (show) View.VISIBLE else View.GONE
        toList.visibility = v
        refreshBtn.visibility = v
        timeline.visibility = v
        uiShown = show
        val sysBars = WindowInsetsCompat.Type.systemBars()
        if (show) bars.show(sysBars) else bars.hide(sysBars)
        hand.removeCallbacks(hideUi)
        if (show) {
            updateChrome()
            hand.postDelayed(hideUi, UI_TIMEOUT_MS)
        }
    }

    private var uiShown = false

    /** 조작판을 만지는 동안에는 숨지 않게 시계를 되감는다. */
    private fun keepUiAwake() {
        if (!uiShown) return
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
        /** 위에서 이만큼이 조작판 토글 자리 */
        private const val TOP_ZONE = 0.30f

        /** 그 아래에서 왼쪽 이만큼이 이전으로 가는 자리 */
        private const val LEFT_ZONE = 0.20f

        /** 문장 번호가 놓이는 자리 — 화면 위에서 이 비율 */
        private const val NUMBER_Y = 0.15f

        /** 화살표 글리프 크기 */
        private const val GLYPH_DP = 32f

        /** 타임라인이 놓이는 자리와 폭 */
        /** 아래에서 15% 자리 */
        /** 아래에서 18% 자리 */
        private const val TIMELINE_Y = 0.82f
        private const val TIMELINE_W = 0.45f

        private const val UI_TIMEOUT_MS = 5_000L
        private const val TOAST_MS = 2_500L
    }
}
