package com.soma.wes.auth.strategy

import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.dto.OAuthUserInfoDto
import org.springframework.stereotype.Component

/**
 * 응답: { "sub": "...", "name": "...", "email": "..." }
 */
@Component
class GoogleUserInfoExtractor : OAuthUserInfoExtractor {

    override val provider = OAuthProvider.GOOGLE

    override fun extract(attributes: Map<String, Any>) = OAuthUserInfoDto(
        provider = provider,
        providerId = attributes.requireString("sub"),
        nickname = attributes["name"] as? String ?: "구글 사용자",
        email = attributes["email"] as? String,
        profileImageUrl = attributes["picture"].asProfileImageUrl(),
    )
}
