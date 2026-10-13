package com.soma.wes.user.domain

/**
 * 인가 권한. "이 요청을 허용할 것인가"를 판단하는 축이다.
 *
 * 사용자의 종류(사진작가/커플 등)와는 별개의 개념이므로 한 필드에 섞지 않는다.
 */
enum class Role {
    USER,
    ADMIN,
    ;

    /**
     * Spring Security는 `hasRole("ADMIN")`을 검사할 때 `ROLE_` 접두사를 붙여 비교한다.
     */
    val authority: String
        get() = "$AUTHORITY_PREFIX$name"

    companion object {
        private const val AUTHORITY_PREFIX = "ROLE_"

        fun from(authority: String): Role =
            valueOf(authority.removePrefix(AUTHORITY_PREFIX))
    }
}
