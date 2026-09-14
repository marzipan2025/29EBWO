package com.artbrain.ebwo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.util.TypedValue
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.artbrain.ebwo.auth.DriveAuth
import com.artbrain.ebwo.drive.DriveApi
import com.artbrain.ebwo.drive.Net
import com.artbrain.ebwo.store.Doc
import com.artbrain.ebwo.store.DocStore
import com.artbrain.ebwo.store.Fetch
import com.artbrain.ebwo.ui.Chime
import com.artbrain.ebwo.ui.Eink
import com.artbrain.ebwo.ui.Fonts
import com.artbrain.ebwo.ui.Glyph
import com.artbrain.ebwo.ui.Ink
import com.artbrain.ebwo.ui.Popup
import com.artbrain.ebwo.ui.Shade
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * 문서 목록.
 *
 * 스크롤을 쓰지 않는다 — 화면에 들어갈 만큼만 놓고 나머지는 이전/다음
 * 단추로 넘긴다. 쪽 수는 화면 높이에서 구하므로 기기가 바뀌어도 맞는다.
 */
class DocListActivity : Activity() {

    private val scope = MainScope()
    private lateinit var store: DocStore

    private lateinit var root: FrameLayout
    private lateinit var box: View
    private lateinit var title: TextView
    private lateinit var rows: LinearLayout
    private lateinit var number: TextView
    private lateinit var empty: TextView
    private lateinit var btnPrev: TextView
    private lateinit var btnNext: TextView
    private lateinit var btnRefresh: TextView

    private var docs: List<Doc> = emptyList()
    private var page = 0
    private var perPage = 1
    private var busy = false

    /** 아래 상태줄에 띄울 말. null 이면 형편에 맞는 기본 말이 나온다. */
    private var status: String? = null
    private val popup by lazy { Popup(root) }
    private lateinit var chime: Chime

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_doclist)
        Eink.applyTheme(this)
        store = DocStore(this)

        // 리더처럼 시스템 막대를 걷는다. 쓸어내릴 때만 잠깐 나온다. 활용공간은
        // 화면 비율로 잡으므로 막대 높이만큼 위가 비는 일이 없다.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideBars()

        root = findViewById(R.id.root)
        rows = findViewById(R.id.rows)
        number = findViewById(R.id.number)
        empty = findViewById(R.id.empty)
        box = findViewById(R.id.box)
        title = findViewById(R.id.title)
        btnPrev = findViewById(R.id.prev)
        btnNext = findViewById(R.id.next)
        btnRefresh = findViewById(R.id.refresh)
        chime = Chime(this, root)

        btnPrev.setOnClickListener { if (page > 0) { page--; render() } }
        btnNext.setOnClickListener { if (page < lastPage()) { page++; render() } }
        btnRefresh.setOnClickListener { refresh() }

        // 이 화면의 글자는 모두 geist — 가는 이탤릭 Geist Mono.
        // 제목만 기울이지 않은 보통 굵기로.
        title.typeface = Fonts.of(this, Fonts.UI_UPRIGHT)
        title.fontVariationSettings = Fonts.REGULAR
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20f)
        title.includeFontPadding = false
        geist(number, 14f)
        geist(btnRefresh, 24f)
        geist(btnPrev, 24f)
        geist(btnNext, 24f)
        root.post { sizeBox() }

        // 지난번에 치워 둔 것은 되돌릴 기회가 지났다. 여기서 쓸어 낸다.
        store.purgeAll()
        docs = store.loadIndex()
        pendingDocId = savedInstanceState?.getString(KEY_PENDING)

        // 한 쪽에 몇 칸이 들어가는지는 자리를 잡은 뒤에야 안다.
        // 통의 높이는 활용공간이 정해진 **뒤에야** 확정된다. 한 번만 재면
        // 상자가 줄기 전의 큰 값을 잡아 칸이 커지고 아래 줄을 침범한다.
        // 높이가 바뀔 때마다 다시 센다.
        rows.viewTreeObserver.addOnGlobalLayoutListener {
            val h = rows.height
            if (h <= 0 || h == measuredRowsH) return@addOnGlobalLayoutListener
            measuredRowsH = h
            // 들어가는 만큼을 세어 칸 높이를 정하고(간격은 이 값으로 고정),
            // 놓기는 하나 적게 한다 — 마지막 칸과 아래 줄 사이가 그만큼 뜬다.
            val fit = max(1, h / Ink.dp(this, ROW_DP).toInt())
            rowH = h / fit
            perPage = fit
            render()
            if (docs.isEmpty() && !askedOnce) { askedOnce = true; refresh() }
        }
    }

    override fun onResume() {
        super.onResume()
        // 계정 고르는 창 따위에서 돌아오면 막대가 다시 나와 있을 수 있다.
        hideBars()
        chime.resume()
        // 읽고 돌아오면 받아 둔 표시가 바뀔 수 있다.
        if (::store.isInitialized && perPage > 0) {
            docs = store.loadIndex()
            render()
        }
    }

    override fun onPause() {
        super.onPause()
        chime.pause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PENDING, pendingDocId)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun hideBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** 이 화면의 모든 글자에 쓰는 꼴 — 가는 이탤릭 Geist Mono. */
    private fun geist(v: TextView, dp: Float) {
        v.typeface = Fonts.of(this, Fonts.UI)
        v.fontVariationSettings = Fonts.THIN
        v.setTextSize(TypedValue.COMPLEX_UNIT_DIP, dp)
        v.includeFontPadding = false
        // TextView 는 가로를 gravity 에 맡기고 세로만 먹 기준으로 맞춘다.
        // translationX 를 걸면 그만큼 반대쪽 여백이 잘려 글리프 끝이 날아간다.
        if (v !is android.widget.Button) v.post { Glyph.centerVertical(v) }
        if (v is android.widget.Button) {
            v.gravity = Gravity.CENTER
            Shade.applyTo(v)
            // 오른쪽 것들은 활용공간의 오른쪽 끝에, 왼쪽 것은 왼쪽 끝에 세운다.
            val toStart = v.id == R.id.prev
            v.post { Glyph.alignEdge(v, toStart) }
        }
    }

    /**
     * 활용공간 — 폭은 화면의 60%. 세로는 **리더에 맞춘다**: 머리(이름·새로고침)의
     * 가운데가 리더의 문장 번호 줄(위에서 15%)에, 발(화살표·쪽 번호)의 가운데가
     * 리더의 시계 줄(아래에서 15%)에 선다. 두 화면을 오갈 때 위아래 줄이 제자리에
     * 있다. 막대를 걷어 화면을 끝까지 쓰므로 비율이 곧 화면 자리다.
     */
    private fun sizeBox() {
        if (root.width <= 0) return
        val h = root.height
        val touch = Ink.dp(this, Ink.TOUCH_DP).toInt()
        val top = (h * Ink.EDGE_Y).toInt() - touch / 2
        val bottom = (h * (1f - Ink.EDGE_Y)).toInt() + touch / 2
        (box.layoutParams as FrameLayout.LayoutParams).let {
            it.width = (root.width * Ink.BOX_FRACTION).toInt()
            it.height = bottom - top
            it.topMargin = top
            it.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            box.layoutParams = it
        }
    }

    /** 칸 하나의 높이. 놓는 칸 수를 줄여도 이 값은 그대로다 — 간격이 안 변한다. */
    private var rowH = 0
    private var measuredRowsH = 0
    private var askedOnce = false

    private fun rowPx(): Int =
        if (rowH > 0) rowH else Ink.dp(this, ROW_DP).toInt()

    private fun lastPage() = max(0, (docs.size - 1) / perPage)

    private fun render() {
        rows.removeAllViews()
        page = page.coerceIn(0, lastPage())

        showStatus()

        if (docs.isEmpty()) {
            number.text = ""
            btnPrev.isEnabled = false
            btnNext.isEnabled = false
            btnPrev.alpha = DIM
            btnNext.alpha = DIM
            return
        }

        val from = page * perPage
        val to = minOf(from + perPage, docs.size)
        val inflater = LayoutInflater.from(this)
        for (i in from until to) addRow(inflater, rows, docs[i])

        number.text = "${page + 1}/${lastPage() + 1}"
        btnPrev.isEnabled = page > 0
        btnNext.isEnabled = page < lastPage()
        // 누를 수 없어도 지운 자리처럼 보이지 않게 옅게 남긴다 — 줄의 균형이
        // 무너지지 않는다.
        btnPrev.alpha = if (btnPrev.isEnabled) 1f else DIM
        btnNext.alpha = if (btnNext.isEnabled) 1f else DIM
    }

    private fun addRow(inflater: LayoutInflater, parent: ViewGroup, doc: Doc) {
        val row = inflater.inflate(R.layout.row_doc, parent, false)
        // 칸이 통을 빈틈없이 나눠 갖게 한다 — 칸 사이에 죽은 자리가 남으면
        // 거기를 눌러도 아무 일이 없어 "안 눌린다" 로 느껴진다.
        row.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, rowPx())
        row.findViewById<TextView>(R.id.name).let {
            it.text = doc.name
            it.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f)
            it.alpha = if (doc.cached) 1f else 0.55f
        }

        val mark = row.findViewById<TextView>(R.id.mark)
        val del = row.findViewById<TextView>(R.id.del)
        geist(mark, 14f)
        geist(del, 20f)

        if (doc.cached) {
            val kb = (store.bodyBytes(doc.id) + 1023) / 1024
            mark.text = "${kb}kb"
            del.visibility = View.VISIBLE
            del.setOnClickListener {
                if (!store.deleteBody(doc.id)) return@setOnClickListener
                docs = store.loadIndex()
                render()
                popup.show(
                    msg = "${doc.name}\n받아 둔 글을 지웠습니다.",
                    undoLabel = "Undo",
                    onUndo = {
                        store.restoreBody(doc.id)
                        docs = store.loadIndex()
                        render()
                    },
                    // 되돌리지 않고 시간이 다 되면 그때 정말 지운다.
                    onExpire = { store.purge(doc.id) },
                    ms = Popup.UNDO_MS,
                )
            }
        } else {
            mark.text = ""
            del.visibility = View.INVISIBLE
        }

        row.setOnClickListener { open(doc) }
        parent.addView(row)
    }

    /** 문서를 연다. 받아 둔 것이 있으면 망 없이도 열린다. */
    private fun open(doc: Doc) {
        if (store.hasBody(doc.id)) {
            startActivity(Intent(this, ReaderActivity::class.java).apply {
                putExtra(ReaderActivity.EXTRA_ID, doc.id)
                putExtra(ReaderActivity.EXTRA_NAME, doc.name)
            })
            return
        }
        if (busy) return
        requestToken(doc.id)
    }

    private fun fetchBody(token: String, doc: Doc) {
        if (busy) return
        var job: kotlinx.coroutines.Job? = null
        val progress = popup.progress("${doc.name}\n받고 있습니다") { job?.cancel() }
        job = scope.launch {
            busy = true
            try {
                Fetch.body(this@DocListActivity, store, token, doc, progress)
                popup.dismiss()
                docs = store.loadIndex()
                say(null)
                render()
                open(doc)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isUnauthorized(e)) DriveAuth.forget()
                say(Net.explain(this@DocListActivity, e, "글을 받지"))
            } finally {
                busy = false
            }
        }
    }

    private fun refresh() {
        if (busy) return
        say("문서 목록을 받고 있습니다…")
        requestToken(null)
    }

    private fun fetchList(token: String) {
        if (busy) return
        scope.launch {
            busy = true
            render()
            try {
                val list = DriveApi(token).listDocs()
                store.saveIndex(list)
                docs = store.loadIndex()
                page = 0
                say(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isUnauthorized(e)) DriveAuth.forget()
                say(e.message ?: "목록을 받지 못했습니다.")
            } finally {
                busy = false
                render()
            }
        }
    }

    private fun isUnauthorized(e: Exception) = e.message?.contains("인증이 만료") == true

    /**
     * 열쇠를 받은 뒤 무엇을 하려던 중이었나.
     *
     * 메모리 콜백으로 들고 있으면 안 된다 — 승낙 화면이 앞에 뜨는 사이
     * 프로세스가 죽으면(이 기기에서는 Boox 의 앱 동결이 실제로 죽인다)
     * 콜백만 사라지고 승낙 결과는 되돌아온다. 그러면 아무 일도 일어나지
     * 않는 것처럼 보인다. 그래서 **저장 상태로 남긴다.**
     *
     * null 이면 목록 새로고침, 값이 있으면 그 문서를 받는다.
     */
    private var pendingDocId: String? = null

    private fun requestToken(forDoc: String?) {
        if (!Net.online(this)) { say(Net.OFFLINE); return }
        pendingDocId = forDoc
        DriveAuth.request(this, onToken = { onToken(it) }, onError = { say(it) })
    }

    /** 열쇠가 들어왔다. 하려던 일을 이어서 한다. */
    private fun onToken(token: String) {
        val docId = pendingDocId
        pendingDocId = null
        if (docId == null) { fetchList(token); return }
        val doc = docs.firstOrNull { it.id == docId }
        if (doc == null) say("문서를 찾을 수 없습니다.") else fetchBody(token, doc)
    }

    @Deprecated("프레임워크 Activity 를 쓰므로 이 갈래가 맞다")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (DriveAuth.onActivityResult(this, requestCode, data,
                onToken = { onToken(it) }, onError = { say(it) })) return
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun say(msg: String?) {
        if (msg == null) { status = null; showStatus(); return }
        popup.show(msg)
    }

    private companion object {
        const val KEY_PENDING = "pendingDocId"


        /** 누를 수 없는 화살표의 옅기 */
        const val DIM = 0.5f

        /** 칸 높이 — 손가락 자리(56dp)의 80% */
        const val ROW_DP = Ink.TOUCH_DP * 0.8f
    }

    private fun showStatus() {
        val msg = when {
            // 목록이 있으면 받는 중인 것은 팝업이 알린다. 칸 위에 겹쳐 쓰지 않는다.
            busy && docs.isEmpty() -> "받고 있습니다…"
            docs.isEmpty() -> "새로고침을 눌러 구글 문서와 epub 을 받아오세요."
            else -> null
        }
        empty.text = msg.orEmpty()
        empty.visibility = if (msg == null) View.GONE else View.VISIBLE
    }
}
