package com.soma.wes.admin.service

import com.soma.wes.admin.config.AdminAuthProperties
import com.soma.wes.admin.domain.AdminAccount
import com.soma.wes.admin.domain.AdminLoginUser
import com.soma.wes.admin.dto.AdminLoginResultDto
import com.soma.wes.admin.dto.request.AdminLoginRequest
import com.soma.wes.admin.dto.request.ChangeAdminPasswordRequest
import com.soma.wes.admin.exception.AdminAuthenticationException
import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.impersonation.service.AdminImpersonationService
import com.soma.wes.admin.repository.AdminAccountRepository
import com.soma.wes.admin.support.AdminPasswordHasher
import java.time.Clock
import java.time.ZonedDateTime
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AdminAuthService(
    private val adminAccountRepository: AdminAccountRepository,
    private val adminSessionService: AdminSessionService,
    private val adminImpersonationService: AdminImpersonationService,
    private val passwordHasher: AdminPasswordHasher,
    private val properties: AdminAuthProperties,
    private val clock: Clock,
) {

    @Transactional(noRollbackFor = [AdminAuthenticationException::class])
    fun login(
        request: AdminLoginRequest,
        sourceAddress: String?,
    ): AdminLoginResultDto {
        val username = normalizeLoginUsername(request.username, request.password, sourceAddress)
        val account = adminAccountRepository.findWithLockByUsername(username)

        if (account == null) {
            passwordHasher.consumeDummyMatch(request.password)
            throw AdminAuthenticationException(AdminErrorCode.INVALID_CREDENTIALS)
        }

        val now = ZonedDateTime.now(clock)
        if (account.isSuspended()) {
            throw AdminAuthenticationException(AdminErrorCode.ACCOUNT_SUSPENDED)
        }
        if (account.isLocked(now)) {
            throw AdminAuthenticationException(AdminErrorCode.ACCOUNT_LOCKED)
        }

        if (!passwordHasher.matches(request.password, account.passwordHash)) {
            val locked = account.recordFailedLogin(now, properties.lockout)
            if (locked) {
                adminSessionService.revokeAll(account.requiredId)
                throw AdminAuthenticationException(AdminErrorCode.ACCOUNT_LOCKED)
            }
            throw AdminAuthenticationException(AdminErrorCode.INVALID_CREDENTIALS)
        }

        if (passwordHasher.needsUpgrade(account.passwordHash)) {
            account.passwordHash = passwordHasher.hash(request.password)
        }
        account.recordSuccessfulLogin(now)

        return adminSessionService.create(account)
    }

    private fun normalizeLoginUsername(
        username: String,
        password: String,
        sourceAddress: String?,
    ): String =
        try {
            AdminAccount.normalizeUsername(username)
        } catch (e: AdminException) {
            passwordHasher.consumeDummyMatch(password)
            throw AdminAuthenticationException(AdminErrorCode.INVALID_CREDENTIALS)
        }

    @Transactional
    fun logout(
        actor: AdminLoginUser,
        rawSessionToken: String,
        sourceAddress: String?,
    ) {
        val account = adminAccountRepository.findById(actor.id).orElse(null)
        adminImpersonationService.endCurrentIfPresent(actor, sourceAddress)
        adminSessionService.revoke(rawSessionToken)
    }

    @Transactional
    fun changePassword(
        adminId: Long,
        request: ChangeAdminPasswordRequest,
        sourceAddress: String?,
    ): AdminLoginResultDto {
        val account = adminAccountRepository.findWithLockById(adminId)
            ?: throw AdminException(AdminErrorCode.ACCOUNT_NOT_FOUND)
        if (!passwordHasher.matches(request.currentPassword, account.passwordHash)) {
            throw AdminAuthenticationException(AdminErrorCode.INVALID_CREDENTIALS)
        }
        if (passwordHasher.matches(request.newPassword, account.passwordHash)) {
            throw AdminException(AdminErrorCode.PASSWORD_REUSE)
        }

        account.changePassword(passwordHasher.hash(request.newPassword), ZonedDateTime.now(clock))
        adminSessionService.revokeAll(adminId)

        return adminSessionService.create(account)
    }

}
