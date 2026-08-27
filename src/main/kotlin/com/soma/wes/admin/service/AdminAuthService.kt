package com.soma.wes.admin.service

import com.soma.wes.admin.config.AdminAuthProperties
import com.soma.wes.admin.audit.domain.AdminAuditAction
import com.soma.wes.admin.audit.domain.AdminAuditOutcome
import com.soma.wes.admin.audit.domain.AdminAuditTargetType
import com.soma.wes.admin.audit.service.AdminAuditService
import com.soma.wes.admin.domain.AdminAccount
import com.soma.wes.admin.domain.AdminAuthEvent
import com.soma.wes.admin.domain.AdminEventType
import com.soma.wes.admin.dto.AdminLoginResult
import com.soma.wes.admin.dto.request.AdminLoginRequest
import com.soma.wes.admin.dto.request.ChangeAdminPasswordRequest
import com.soma.wes.admin.exception.AdminAuthenticationException
import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.domain.AdminLoginUser
import com.soma.wes.admin.impersonation.service.AdminImpersonationService
import com.soma.wes.admin.repository.AdminAccountRepository
import com.soma.wes.admin.repository.AdminAuthEventRepository
import com.soma.wes.admin.support.AdminPasswordHasher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime

@Service
class AdminAuthService(
    private val adminAccountRepository: AdminAccountRepository,
    private val adminAuthEventRepository: AdminAuthEventRepository,
    private val adminAuditService: AdminAuditService,
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
    ): AdminLoginResult {
        val username = normalizeLoginUsername(request.username, request.password, sourceAddress)
        val account = adminAccountRepository.findWithLockByUsername(username)

        if (account == null) {
            passwordHasher.consumeDummyMatch(request.password)
            recordLoginFailure(username, null, sourceAddress)
            throw AdminAuthenticationException(AdminErrorCode.INVALID_CREDENTIALS)
        }

        val now = ZonedDateTime.now(clock)
        if (account.isSuspended()) {
            recordLoginFailure(username, account.requiredId, sourceAddress)
            throw AdminAuthenticationException(AdminErrorCode.ACCOUNT_SUSPENDED)
        }
        if (account.isLocked(now)) {
            recordLoginFailure(username, account.requiredId, sourceAddress)
            throw AdminAuthenticationException(AdminErrorCode.ACCOUNT_LOCKED)
        }

        if (!passwordHasher.matches(request.password, account.passwordHash)) {
            val locked = account.recordFailedLogin(now, properties.lockout)
            recordLoginFailure(username, account.requiredId, sourceAddress)
            if (locked) {
                adminSessionService.revokeAll(account.requiredId)
                recordEvent(
                    eventType = AdminEventType.ACCOUNT_LOCKED,
                    actorAdminId = account.requiredId,
                    targetAdminId = account.requiredId,
                    username = account.username,
                    sourceAddress = sourceAddress,
                    successful = true,
                )
                throw AdminAuthenticationException(AdminErrorCode.ACCOUNT_LOCKED)
            }
            throw AdminAuthenticationException(AdminErrorCode.INVALID_CREDENTIALS)
        }

        if (passwordHasher.needsUpgrade(account.passwordHash)) {
            account.passwordHash = passwordHasher.hash(request.password)
        }
        account.recordSuccessfulLogin(now)
        recordEvent(
            eventType = AdminEventType.LOGIN_SUCCEEDED,
            actorAdminId = account.requiredId,
            targetAdminId = account.requiredId,
            username = account.username,
            sourceAddress = sourceAddress,
            successful = true,
        )

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
            recordLoginFailure(username.trim().take(AdminAccount.USERNAME_MAX_LENGTH), null, sourceAddress)
            throw AdminAuthenticationException(AdminErrorCode.INVALID_CREDENTIALS)
        }

    private fun recordLoginFailure(
        username: String,
        adminId: Long?,
        sourceAddress: String?,
    ) {
        recordEvent(
            eventType = AdminEventType.LOGIN_FAILED,
            actorAdminId = adminId,
            targetAdminId = adminId,
            username = username,
            sourceAddress = sourceAddress,
            successful = false,
        )
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
        recordEvent(
            eventType = AdminEventType.LOGOUT,
            actorAdminId = actor.id,
            targetAdminId = actor.id,
            username = account?.username,
            sourceAddress = sourceAddress,
            successful = true,
        )
    }

    @Transactional
    fun changePassword(
        adminId: Long,
        request: ChangeAdminPasswordRequest,
        sourceAddress: String?,
    ): AdminLoginResult {
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
        recordEvent(
            eventType = AdminEventType.PASSWORD_CHANGED,
            actorAdminId = adminId,
            targetAdminId = adminId,
            username = account.username,
            sourceAddress = sourceAddress,
            successful = true,
        )

        return adminSessionService.create(account)
    }

    private fun recordEvent(
        eventType: AdminEventType,
        actorAdminId: Long?,
        targetAdminId: Long?,
        username: String?,
        sourceAddress: String?,
        successful: Boolean,
    ) {
        adminAuthEventRepository.save(
            AdminAuthEvent.of(
                eventType = eventType,
                actorAdminId = actorAdminId,
                targetAdminId = targetAdminId,
                usernameSnapshot = username,
                sourceAddress = sourceAddress,
                reason = null,
                successful = successful,
            ),
        )
        adminAuditService.recordEvent(
            action = AdminAuditAction.valueOf(eventType.name),
            outcome = if (successful) AdminAuditOutcome.SUCCESS else AdminAuditOutcome.FAILURE,
            actorAdminId = actorAdminId,
            actorUsername = username,
            targetType = if (targetAdminId == null) {
                AdminAuditTargetType.AUTHENTICATION
            } else {
                AdminAuditTargetType.ADMIN_ACCOUNT
            },
            targetId = targetAdminId?.toString() ?: username,
            targetLabel = username,
            reason = null,
            sourceAddress = sourceAddress,
            changedFields = when (eventType) {
                AdminEventType.LOGIN_SUCCEEDED -> listOf("failedLoginAttempts", "lockedUntil", "lastLoginAt")
                AdminEventType.ACCOUNT_LOCKED -> listOf("failedLoginAttempts", "lockedUntil")
                AdminEventType.PASSWORD_CHANGED -> listOf("password", "mustChangePassword", "failedLoginAttempts", "lockedUntil")
                else -> emptyList()
            },
        )
    }
}
