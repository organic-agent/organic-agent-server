package com.soma.wes.auth.strategy

import com.soma.wes.auth.dto.OAuthUserInfo
import com.soma.wes.auth.domain.OAuthProvider
import org.springframework.stereotype.Component

/**
 * 응답: { "response": { "id": "...", "nickname": "...", "email": "..." } }
 * 실제 정보가 response 안에 한 겹 더 들어 있다.
 */
@Component
class NaverUserInfoExtractor : OAuthUserInfoExtractor {

    override val provider = OAuthProvider.NAVER

    override fun extract(attributes: Map<String, Any>): OAuthUserInfo {
        val response = attributes.requireMap("response")

        return OAuthUserInfo(
            provider = provider,
            providerId = response.requireString("id"),
            nickname = response["nickname"] as? String ?: "네이버 사용자",
            email = response["email"] as? String,
            profileImageUrl = response["profile_image"].asProfileImageUrl(),
        )
    }
}
