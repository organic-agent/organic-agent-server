package com.soma.wes.user.dto.response

import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.user.domain.Role
import com.soma.wes.user.domain.User
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

data class UserResponse(
    val id: Long,
    val provider: OAuthProvider,
    val nickname: String,
    @field:Schema(
        description = "소셜 계정의 이메일로, 로그인할 때마다 provider 값으로 덮어쓴다. provider가 주지 않으면(제공 미동의 등) null이다.",
        example = "bride@example.com",
    )
    val email: String?,
    val role: Role,
    @field:Schema(description = "가입(첫 로그인) 시각. 저장할 때 서버가 채운다.", example = "2026-03-01T12:00:00+09:00")
    val createdAt: ZonedDateTime?,
    val workspaces: List<UserWorkspaceResponse> = emptyList(),
    @field:Schema(
        description = "소셜 프로필 사진 URL로, 로그인할 때마다 provider 값으로 덮어쓴다. 제공에 동의하지 않았거나 사진이 없거나 http(s) URL이 아니면 null이다.",
        example = "https://lh3.googleusercontent.com/a/ACg8ocJ_example=s96-c",
    )
    val profileImageUrl: String? = null,
    @field:Schema(
        description = "부부 화면에서 이 사람을 칠하는 색 키(소문자). 아직 고르지 않았으면 null이고, 웹이 들어온 순서로 기본 색을 준다.",
        example = "sky",
        allowableValues = ["sky", "coral", "apricot", "lemon", "sage", "mint", "rose"],
    )
    val color: String? = null,
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
            color = user.color?.key,
        )
    }
}
