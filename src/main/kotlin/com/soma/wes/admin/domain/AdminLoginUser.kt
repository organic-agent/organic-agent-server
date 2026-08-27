package com.soma.wes.admin.domain

import org.springframework.security.core.GrantedAuthority
import java.util.UUID

data class AdminLoginUser(
    val id: Long,
    val username: String,
    val displayName: String,
    val mustChangePassword: Boolean,
    val adminSessionId: UUID,
    val authorities: Collection<GrantedAuthority>,
)
