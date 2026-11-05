package com.soma.wes.auth.service.port

import com.soma.wes.auth.domain.Subject
import com.soma.wes.auth.dto.TokenClaimsDto
import java.time.Duration

/**
 * JWT를 서명하고 검증한다.
 *
 * "어떤 종류의 토큰을 얼마나 살려둘 것인가", "어떤 클레임을 담을 것인가" 같은 정책은 모른다.
 * 그건 [com.soma.wes.auth.service.AuthTokenProvider]가 정한다.
 */
interface TokenSigner {
    fun sign(subject: Subject, expiresIn: Duration, claims: Map<String, String>): String

    /** 서명과 만료를 검증하고 클레임을 돌려준다. 실패하면 [com.soma.wes.auth.exception.TokenException]을 던진다. */
    fun verify(token: String): TokenClaimsDto
}
