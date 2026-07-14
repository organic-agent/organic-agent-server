package com.soma.wes.user.repository

import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.user.domain.User
import org.springframework.data.jpa.repository.JpaRepository

interface UserRepository : JpaRepository<User, Long> {

    fun findByProviderAndProviderId(provider: OAuthProvider, providerId: String): User?
}
