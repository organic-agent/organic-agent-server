package com.soma.wes.admin.service

import com.soma.wes.admin.config.AdminAuthProperties
import com.soma.wes.admin.domain.AdminAccount
import com.soma.wes.admin.domain.AdminLoginUser
import com.soma.wes.admin.domain.AdminSession
import com.soma.wes.admin.dto.AdminLoginResult
import com.soma.wes.admin.dto.response.AdminSessionResponse
import com.soma.wes.admin.exception.AdminAuthenticationException
import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.repository.AdminAccountRepository
import com.soma.wes.admin.repository.AdminSessionRepository
import com.soma.wes.admin.support.AdminSecretGenerator
import com.soma.wes.admin.support.AdminSessionTokenHasher
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime

@Service
class AdminSessionService(
    private val adminSessionRepository: AdminSessionRepository,
    private val adminAccountRepository: AdminAccountRepository,
    private val secretGenerator: AdminSecretGenerator,
    private val tokenHasher: AdminSessionTokenHasher,
    private val properties: AdminAuthProperties,
    private val clock: Clock,
) {

    @Transactional
    fun create(account: AdminAccount): AdminLoginResult {
        val now = ZonedDateTime.now(clock)
        val rawToken = secretGenerator.generate()
        val session = adminSessionRepository.save(
            AdminSession.of(
                tokenHash = tokenHasher.hash(rawToken),
                adminId = account.requiredId,
                now = now,
                absoluteTtl = properties.session.absoluteTtl,
            ),
        )

        return AdminLoginResult(
            rawSessionToken = rawToken,
            response = AdminSessionResponse.of(account, session.absoluteExpiresAt),
        )
    }

    @Transactional
    fun authenticate(rawToken: String): Authentication {
        val now = ZonedDateTime.now(clock)
        val session = adminSessionRepository.findWithLockByTokenHash(tokenHasher.hash(rawToken))
            ?: throw AdminAuthenticationException(AdminErrorCode.SESSION_INVALID)
        if (!session.isUsable(now, properties.session.idleTtl)) {
            throw AdminAuthenticationException(AdminErrorCode.SESSION_INVALID)
        }

        val account = adminAccountRepository.findById(session.adminId)
            .orElseThrow { AdminAuthenticationException(AdminErrorCode.SESSION_INVALID) }
        if (account.isSuspended()) {
            throw AdminAuthenticationException(AdminErrorCode.ACCOUNT_SUSPENDED)
        }

        session.touch(now)

        val role = if (account.mustChangePassword) ROLE_PASSWORD_CHANGE else ROLE_SUPER_ADMIN
        val authorities = listOf(SimpleGrantedAuthority(role))
        val principal = AdminLoginUser(
            id = account.requiredId,
            username = account.username,
            displayName = account.displayName,
            mustChangePassword = account.mustChangePassword,
            authorities = authorities,
        )
        return UsernamePasswordAuthenticationToken(principal, "", authorities)
    }

    @Transactional(readOnly = true)
    fun getCurrent(
        adminId: Long,
        rawToken: String,
    ): AdminSessionResponse {
        val session = adminSessionRepository.findById(tokenHasher.hash(rawToken))
            .orElseThrow { AdminAuthenticationException(AdminErrorCode.SESSION_INVALID) }
        if (session.adminId != adminId) {
            throw AdminAuthenticationException(AdminErrorCode.SESSION_INVALID)
        }
        val account = adminAccountRepository.findById(adminId)
            .orElseThrow { AdminAuthenticationException(AdminErrorCode.SESSION_INVALID) }

        return AdminSessionResponse.of(account, session.absoluteExpiresAt)
    }

    @Transactional
    fun revoke(rawToken: String) {
        val session = adminSessionRepository.findWithLockByTokenHash(tokenHasher.hash(rawToken)) ?: return
        session.revoke(ZonedDateTime.now(clock))
    }

    @Transactional
    fun revokeAll(adminId: Long) {
        val now = ZonedDateTime.now(clock)
        adminSessionRepository.findAllByAdminIdAndRevokedAtIsNull(adminId)
            .forEach { it.revoke(now) }
    }

    companion object {
        private const val ROLE_SUPER_ADMIN = "ROLE_SUPER_ADMIN"
        private const val ROLE_PASSWORD_CHANGE = "ROLE_ADMIN_PASSWORD_CHANGE"
    }
}
