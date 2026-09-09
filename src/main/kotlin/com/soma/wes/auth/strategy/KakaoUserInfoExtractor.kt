package com.soma.wes.auth.strategy

import com.soma.wes.auth.dto.OAuthUserInfo
import com.soma.wes.auth.domain.OAuthProvider
import org.springframework.stereotype.Component

/**
 * 응답: { "id": 12345, "kakao_account": { "email": "...", "profile": { "nickname": "..." } } }
 * 이메일은 사용자가 제공에 동의하지 않으면 아예 오지 않는다.
 */
@Component
class KakaoUserInfoExtractor : OAuthUserInfoExtractor {

    override val provider = OAuthProvider.KAKAO

    override fun extract(attributes: Map<String, Any>): OAuthUserInfo {
        val account = attributes["kakao_account"] as? Map<*, *>
        val profile = account?.get("profile") as? Map<*, *>

        return OAuthUserInfo(
            provider = provider,
            // 카카오의 id는 숫자로 내려온다.
            providerId = attributes.requireString("id"),
            nickname = profile?.get("nickname") as? String ?: "카카오 사용자",
            email = account?.get("email") as? String,
            profileImageUrl = profile?.get("profile_image_url").asProfileImageUrl(),
        )
    }
}
