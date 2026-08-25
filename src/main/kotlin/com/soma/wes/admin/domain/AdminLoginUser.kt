package com.soma.wes.admin.domain

import org.springframework.security.core.GrantedAuthority

data class AdminLoginUser(
    val id: Long,
    val username: String,
    val displayName: String,
    val mustChangePassword: Boolean,
    val authorities: Collection<GrantedAuthority>,
)
