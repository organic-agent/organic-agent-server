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
) {

    companion object {
        fun from(user: User) = UserResponse(
            id = checkNotNull(user.id) { "저장되지 않은 사용자는 응답할 수 없습니다." },
            provider = user.provider,
            nickname = user.nickname,
            email = user.email,
            role = user.role,
            createdAt = user.createdAt,
        )
    }
}
