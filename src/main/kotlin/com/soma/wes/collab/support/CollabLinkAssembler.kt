package com.soma.wes.collab.support

import com.soma.wes.collab.config.CollabProperties
import org.springframework.stereotype.Component

/**
 * 토큰을 하객에게 그대로 보낼 수 있는 링크로 만든다.
 *
 * 서버가 토큰만 내려주고 프론트가 도메인을 붙이게 하면, 같은 조립 규칙이 링크 복사 버튼과
 * 카카오톡 공유와 청첩장 QR에 각각 흩어진다. 그중 하나만 경로를 놓쳐도 그 경로로 받은
 * 사람에게만 깨진 링크가 간다.
 *
 * 토큰은 URL-safe Base64([com.soma.wes.global.SecureTokenGenerator])라 경로에 그대로 실어도
 * 인코딩이 필요 없다.
 */
@Component
class CollabLinkAssembler(
    private val properties: CollabProperties,
) {

    fun assemble(shareToken: String): String = "${properties.baseUrl.trimEnd('/')}/$shareToken"
}
