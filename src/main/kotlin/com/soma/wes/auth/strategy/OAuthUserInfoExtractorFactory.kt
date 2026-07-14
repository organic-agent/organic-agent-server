package com.soma.wes.auth.strategy

import com.soma.wes.auth.exception.AuthErrorCode
import com.soma.wes.auth.exception.OAuthException
import com.soma.wes.auth.domain.OAuthProvider
import org.springframework.stereotype.Component

/**
 * provider에 맞는 추출기를 골라준다.
 *
 * Spring이 [OAuthUserInfoExtractor] 빈을 모두 주입해주므로, provider를 추가할 때
 * 이 팩토리를 고칠 필요 없이 구현체 하나만 만들면 된다.
 */
@Component
class OAuthUserInfoExtractorFactory(
    extractors: List<OAuthUserInfoExtractor>,
) {

    private val byProvider = extractors.associateBy { it.provider }

    fun resolve(provider: OAuthProvider): OAuthUserInfoExtractor =
        byProvider[provider] ?: throw OAuthException(AuthErrorCode.PROVIDER_NOT_SUPPORTED)
}
