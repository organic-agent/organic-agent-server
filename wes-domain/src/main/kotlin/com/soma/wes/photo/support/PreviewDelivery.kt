package com.soma.wes.photo.support

import org.springframework.stereotype.Component

/**
 * 이 요청이 미리보기를 서명 URL로 받기를 원하는지(#336 계약 `preview=signed`). 쿠키를 못 받는 환경(인앱 브라우저 정책 · 발급 실패)에서
 * 웹이 옛 경로로 물러나는 문이다. 요청 스레드에만 산다 — [PreviewDeliveryFilter]가 요청 처음에 정하고 끝에 지운다.
 *
 * 요청 밖(스케줄러·비동기)에서는 언제나 false라 서버 설정대로 간다.
 */
@Component
class PreviewDelivery {

    private val forcedSigned = ThreadLocal.withInitial { false }

    fun isSignedForced(): Boolean = forcedSigned.get()

    fun forceSigned(value: Boolean) {
        forcedSigned.set(value)
    }

    fun clear() {
        forcedSigned.remove()
    }
}
