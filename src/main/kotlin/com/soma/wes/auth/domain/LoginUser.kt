package com.soma.wes.auth.domain

import org.springframework.security.core.GrantedAuthority

/**
 * 인증된 요청의 주체. 컨트롤러에서 `@AuthenticationPrincipal loginUser: LoginUser`로 꺼내 쓴다.
 *
 * 권한은 [authorities]에만 담는다. `Role`을 따로 들고 있으면 같은 정보가 두 곳에 생겨 어긋날 수 있다.
 */
data class LoginUser(
    val id: Long,
    val providerId: String,
    val authorities: Collection<GrantedAuthority>,
)
