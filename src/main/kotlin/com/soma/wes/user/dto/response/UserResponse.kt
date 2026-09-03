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
            id = user.requiredId,
            provider = user.provider,
            nickname = user.nickname,
            email = user.email,
            role = user.role,
            createdAt = user.createdAt,
        )
    }
}
