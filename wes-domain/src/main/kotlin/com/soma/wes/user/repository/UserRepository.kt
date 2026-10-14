package com.soma.wes.user.repository

import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.user.domain.User
import com.soma.wes.user.exception.UserErrorCode
import com.soma.wes.user.exception.UserException
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import jakarta.persistence.LockModeType

interface UserRepository : JpaRepository<User, Long> {

    fun findByProviderAndProviderId(provider: OAuthProvider, providerId: String): User?

    /** 계정당 무료 갤러리 개설과 쿠폰 사용을 같은 사용자 행으로 직렬화한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockById(id: Long): User?
}

/**
 * [JpaRepository.findById]에 "없으면 404"를 붙인 것.
 *
 * [com.soma.wes.auth.service.AuthTokenProvider.parseUser]는 이것을 쓰지 않는다. 같은 조회지만
 * 토큰의 주인이 사라진 것이라 404가 아니라 401을 던져야 한다.
 */
fun UserRepository.requireById(id: Long): User =
    findById(id).orElseThrow { UserException(UserErrorCode.USER_NOT_FOUND) }

fun UserRepository.requireWithLockById(id: Long): User =
    findWithLockById(id) ?: throw UserException(UserErrorCode.USER_NOT_FOUND)
