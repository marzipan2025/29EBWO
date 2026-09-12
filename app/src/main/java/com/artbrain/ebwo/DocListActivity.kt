package com.artbrain.ebwo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.artbrain.ebwo.auth.DriveAuth
import com.artbrain.ebwo.drive.DriveApi
import com.artbrain.ebwo.store.Doc
import com.artbrain.ebwo.store.DocStore
import com.artbrain.ebwo.ui.Ink
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

    private lateinit var rows: LinearLayout
    private lateinit var pageInfo: TextView
    private lateinit var empty: TextView
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    private lateinit var btnRefresh: Button

    private var docs: List<Doc> = emptyList()
    private var page = 0
    private var perPage = 1
    private var busy = false

    /** 아래 상태줄에 띄울 말. null 이면 형편에 맞는 기본 말이 나온다. */
    private var status: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_doclist)
        store = DocStore(this)

        rows = findViewById(R.id.rows)
        pageInfo = findViewById(R.id.pageInfo)
        empty = findViewById(R.id.empty)
        btnPrev = findViewById(R.id.prev)
        btnNext = findViewById(R.id.next)
        btnRefresh = findViewById(R.id.refresh)

        btnPrev.setOnClickListener { if (page > 0) { page--; render() } }
        btnNext.setOnClickListener { if (page < lastPage()) { page++; render() } }
        btnRefresh.setOnClickListener { refresh() }

        docs = store.loadIndex()
        pendingDocId = savedInstanceState?.getString(KEY_PENDING)

        // 한 쪽에 몇 칸이 들어가는지는 자리를 잡은 뒤에야 안다.
        rows.post {
            val rowH = Ink.dp(this, Ink.TOUCH_DP).toInt()
            perPage = max(1, rows.height / rowH)
            render()
            if (docs.isEmpty()) refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        // 읽고 돌아오면 받아 둔 표시가 바뀔 수 있다.
        if (::store.isInitialized && perPage > 0) {
            docs = store.loadIndex()
            render()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_PENDING, pendingDocId)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun lastPage() = max(0, (docs.size - 1) / perPage)

    private fun render() {
        rows.removeAllViews()
        page = page.coerceIn(0, lastPage())

        showStatus()

        if (docs.isEmpty()) {
            pageInfo.text = ""
            btnPrev.isEnabled = false
            btnNext.isEnabled = false
            return
        }

        val from = page * perPage
        val to = minOf(from + perPage, docs.size)
        val inflater = LayoutInflater.from(this)
        for (i in from until to) addRow(inflater, rows, docs[i])

        pageInfo.text = "${page + 1} / ${lastPage() + 1}"
        btnPrev.isEnabled = page > 0
        btnNext.isEnabled = page < lastPage()
    }

    private fun addRow(inflater: LayoutInflater, parent: ViewGroup, doc: Doc) {
        val row = inflater.inflate(R.layout.row_doc, parent, false)
        row.findViewById<TextView>(R.id.name).text = doc.name

        val mark = row.findViewById<TextView>(R.id.mark)
        val del = row.findViewById<Button>(R.id.del)

        if (doc.cached) {
            val kb = (store.bodyBytes(doc.id) + 1023) / 1024
            mark.text = "${kb}KB"
            del.visibility = View.VISIBLE
            del.setOnClickListener {
                store.deleteBody(doc.id)
                docs = store.loadIndex()
                render()
                say("${doc.name} — 받아 둔 글을 지웠습니다.")
            }
        } else {
            mark.text = ""
            del.visibility = View.GONE
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
        say("${doc.name} — 받고 있습니다…")
        requestToken(doc.id)
    }

    private fun fetchBody(token: String, doc: Doc) {
        if (busy) return
        scope.launch {
            busy = true
            try {
                val text = DriveApi(token).exportText(doc.id)
                store.writeBody(doc.id, text)
                docs = store.loadIndex()
                say(null)
                render()
                open(doc)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isUnauthorized(e)) DriveAuth.forget()
                say(e.message ?: "받지 못했습니다.")
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
        status = msg
        showStatus()
    }

    private companion object {
        const val KEY_PENDING = "pendingDocId"
    }

    private fun showStatus() {
        val msg = status ?: when {
            busy -> "받고 있습니다…"
            docs.isEmpty() -> "새로고침을 눌러 구글 문서를 받아오세요."
            else -> null
        }
        empty.text = msg.orEmpty()
        empty.visibility = if (msg == null) View.GONE else View.VISIBLE
    }
}
