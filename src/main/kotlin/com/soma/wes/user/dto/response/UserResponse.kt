package com.soma.wes.user.dto.response

import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.user.domain.Role
import com.soma.wes.user.domain.User
import com.soma.wes.user.domain.UserType
import java.time.ZonedDateTime

data class UserResponse(
    val id: Long,
    val provider: OAuthProvider,
    val nickname: String,
    val email: String?,
    val role: Role,
    /** 온보딩 전에는 null이다. 클라이언트는 이 값으로 온보딩 화면 진입 여부를 판단한다. */
    val userType: UserType?,
    val createdAt: ZonedDateTime?,
) {

    companion object {
        fun from(user: User) = UserResponse(
            id = user.requiredId,
            provider = user.provider,
            nickname = user.nickname,
            email = user.email,
            role = user.role,
            userType = user.userType,
            createdAt = user.createdAt,
        )
    }
}
