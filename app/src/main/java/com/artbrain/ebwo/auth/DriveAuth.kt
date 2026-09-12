package com.artbrain.ebwo.auth

import android.app.Activity
import android.content.Intent
import com.artbrain.ebwo.drive.DriveApi
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope

/**
 * 기기에 이미 든 구글 계정으로 드라이브 열쇠를 받아 온다.
 *
 * 다른 앱(구글 문서 따위)의 열쇠를 빌려 쓰는 것이 아니다 — 그것은 앱 우리에
 * 갇혀 있어 읽을 수 없다. 대신 **기기에 등록된 계정**을 두고 우리 앱 이름으로
 * 열쇠를 새로 받는다. 웹 로그인 칸을 띄우지 않고 계정 고르는 창만 뜬다.
 *
 * 처음 한 번은 승낙 창이 뜬다([AuthorizationResult.hasResolution]). 그때는
 * 그 창을 띄우고 [onActivityResult] 로 돌아온 것을 받는다.
 */
object DriveAuth {

    const val REQ_AUTHORIZE = 4901

    /** 받아 둔 열쇠. 한 시간쯤 산다. 만료는 401 을 보고 안다. */
    @Volatile
    private var cached: String? = null

    fun cachedToken(): String? = cached

    fun forget() { cached = null }

    /**
     * 열쇠를 청한다. 이미 승낙해 둔 계정이면 곧바로 [onToken] 이 온다.
     * 승낙 창이 필요하면 창을 띄우고, 결과는 [onActivityResult] 로 온다.
     */
    fun request(
        activity: Activity,
        onToken: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        cached?.let { onToken(it); return }

        val req = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DriveApi.SCOPE_DRIVE_READONLY)))
            .build()

        Identity.getAuthorizationClient(activity)
            .authorize(req)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val sender = result.pendingIntent?.intentSender
                    if (sender == null) {
                        onError("승낙 창을 열 수 없습니다.")
                        return@addOnSuccessListener
                    }
                    runCatching {
                        activity.startIntentSenderForResult(sender, REQ_AUTHORIZE, null, 0, 0, 0)
                    }.onFailure { onError("승낙 창을 열지 못했습니다: ${it.message}") }
                } else {
                    val t = result.accessToken
                    if (t.isNullOrEmpty()) onError("열쇠를 받지 못했습니다.")
                    else { cached = t; onToken(t) }
                }
            }
            .addOnFailureListener { onError(hint(it.message)) }
    }

    /** 승낙 창에서 돌아온 것을 받는다. 우리 요청이 아니면 false 를 돌려준다. */
    fun onActivityResult(
        activity: Activity,
        requestCode: Int,
        data: Intent?,
        onToken: (String) -> Unit,
        onError: (String) -> Unit,
    ): Boolean {
        if (requestCode != REQ_AUTHORIZE) return false
        if (data == null) { onError("승낙이 취소되었습니다."); return true }
        runCatching {
            Identity.getAuthorizationClient(activity)
                .getAuthorizationResultFromIntent(data)
        }.onSuccess { result ->
            val t = result.accessToken
            if (t.isNullOrEmpty()) onError("열쇠를 받지 못했습니다.")
            else { cached = t; onToken(t) }
        }.onFailure { onError(hint(it.message)) }
        return true
    }

    /**
     * 구글이 주는 말만으로는 무엇을 고쳐야 할지 알 수 없다. 설정이 덜 된
     * 흔한 경우를 짚어 준다.
     */
    private fun hint(msg: String?): String {
        val m = msg.orEmpty()
        return when {
            "10" in m || "DEVELOPER_ERROR" in m ->
                "OAuth 설정이 맞지 않습니다. 패키지 이름과 디버그 SHA-1 이 " +
                    "구글 클라우드의 Android 클라이언트와 같은지 확인하세요. ($m)"
            "16" in m || "API_NOT_CONNECTED" in m ->
                "구글 플레이 서비스를 쓸 수 없습니다. ($m)"
            else -> "인증 실패: ${m.ifEmpty { "알 수 없는 까닭" }}"
        }
    }
}
