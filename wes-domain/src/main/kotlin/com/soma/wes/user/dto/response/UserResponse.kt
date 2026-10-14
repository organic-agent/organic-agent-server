package com.soma.wes.user.dto.response

import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.user.domain.Role
import com.soma.wes.user.domain.User
import java.time.ZonedDateTime

data class UserResponse(
    val id: Long,
    val provider: OAuthProvider,
    val nickname: String,
    val email: String?,
    val role: Role,
    val createdAt: ZonedDateTime?,
    val workspaces: List<UserWorkspaceResponse> = emptyList(),
    /** 소셜 프로필 사진. 제공에 동의하지 않았거나 사진이 없으면 null이다. */
    val profileImageUrl: String? = null,
) {

    companion object {
        fun from(user: User) = UserResponse(
            id = user.requiredId,
            provider = user.provider,
            nickname = user.nickname,
            email = user.email,
            role = user.role,
            createdAt = user.createdAt,
            profileImageUrl = user.profileImageUrl,
        )
    }
}
