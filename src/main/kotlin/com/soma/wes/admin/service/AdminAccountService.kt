package com.soma.wes.admin.service

import com.soma.wes.admin.domain.AdminAccount
import com.soma.wes.admin.domain.AdminAccountStatus
import com.soma.wes.admin.domain.AdminAuthEvent
import com.soma.wes.admin.domain.AdminEventType
import com.soma.wes.admin.audit.domain.AdminAuditAction
import com.soma.wes.admin.audit.domain.AdminAuditOutcome
import com.soma.wes.admin.audit.domain.AdminAuditTargetType
import com.soma.wes.admin.audit.service.AdminAuditService
import com.soma.wes.admin.audit.support.AdminAccountAuditSnapshot
import com.soma.wes.admin.dto.request.AdminReasonRequest
import com.soma.wes.admin.dto.request.ChangeAdminStatusRequest
import com.soma.wes.admin.dto.request.CreateAdminAccountRequest
import com.soma.wes.admin.dto.response.AdminAccountListResponse
import com.soma.wes.admin.dto.response.AdminAccountResponse
import com.soma.wes.admin.dto.response.AdminTemporaryPasswordResponse
import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.repository.AdminAccountRepository
import com.soma.wes.admin.repository.AdminAuthEventRepository
import com.soma.wes.admin.support.AdminPasswordHasher
import com.soma.wes.admin.support.AdminSecretGenerator
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime

@Service
class AdminAccountService(
    private val adminAccountRepository: AdminAccountRepository,
    private val adminAuthEventRepository: AdminAuthEventRepository,
    private val adminAuditService: AdminAuditService,
    private val adminSessionService: AdminSessionService,
    private val passwordHasher: AdminPasswordHasher,
    private val secretGenerator: AdminSecretGenerator,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun getAccounts(): AdminAccountListResponse =
        AdminAccountListResponse(
            accounts = adminAccountRepository.findAllByOrderByUsernameAsc()
                .map(AdminAccountResponse::from),
        )

    @Transactional
    fun create(
        actorAdminId: Long,
        request: CreateAdminAccountRequest,
        sourceAddress: String?,
    ): AdminTemporaryPasswordResponse {
        validateReason(request.reason)
        val username = AdminAccount.normalizeUsername(request.username)
        if (adminAccountRepository.existsByUsername(username)) {
            throw AdminException(AdminErrorCode.USERNAME_ALREADY_EXISTS)
        }

        val temporaryPassword = secretGenerator.generate()
        val account = try {
            adminAccountRepository.saveAndFlush(
                AdminAccount.of(
                    username = username,
                    displayName = request.displayName,
                    passwordHash = passwordHasher.hash(temporaryPassword),
                    now = ZonedDateTime.now(clock),
                    createdByAdminId = actorAdminId,
                ),
            )
        } catch (e: DataIntegrityViolationException) {
            throw AdminException(AdminErrorCode.USERNAME_ALREADY_EXISTS)
        }

        recordLegacyEvent(AdminEventType.ACCOUNT_CREATED, actorAdminId, account, request.reason, sourceAddress)
        adminAuditService.recordMutation(
            action = AdminAuditAction.ACCOUNT_CREATED,
            actorAdminId = actorAdminId,
            targetType = AdminAuditTargetType.ADMIN_ACCOUNT,
            targetId = account.requiredId.toString(),
            targetLabel = account.username,
            reason = request.reason,
            sourceAddress = sourceAddress,
            before = null,
            after = AdminAccountAuditSnapshot.from(account).toMap(),
        )
        return AdminTemporaryPasswordResponse(
            account = AdminAccountResponse.from(account),
            temporaryPassword = temporaryPassword,
        )
    }

    @Transactional
    fun changeStatus(
        actorAdminId: Long,
        targetAdminId: Long,
        request: ChangeAdminStatusRequest,
        sourceAddress: String?,
    ): AdminAccountResponse {
        validateReason(request.reason)
        val account = requireWithLock(targetAdminId)
        val before = AdminAccountAuditSnapshot.from(account).toMap()

        val eventType = when (request.status) {
            AdminAccountStatus.ACTIVE -> {
                account.activate()
                AdminEventType.ACCOUNT_ACTIVATED
            }
            AdminAccountStatus.SUSPENDED -> {
                account.suspend()
                adminSessionService.revokeAll(targetAdminId)
                AdminEventType.ACCOUNT_SUSPENDED
            }
        }
        recordLegacyEvent(eventType, actorAdminId, account, request.reason, sourceAddress)
        adminAuditService.recordMutation(
            action = AdminAuditAction.valueOf(eventType.name),
            actorAdminId = actorAdminId,
            targetType = AdminAuditTargetType.ADMIN_ACCOUNT,
            targetId = account.requiredId.toString(),
            targetLabel = account.username,
            reason = request.reason,
            sourceAddress = sourceAddress,
            before = before,
            after = AdminAccountAuditSnapshot.from(account).toMap(),
        )
        return AdminAccountResponse.from(account)
    }

    @Transactional
    fun unlock(
        actorAdminId: Long,
        targetAdminId: Long,
        request: AdminReasonRequest,
        sourceAddress: String?,
    ): AdminAccountResponse {
        validateReason(request.reason)
        val account = requireWithLock(targetAdminId)
        val before = AdminAccountAuditSnapshot.from(account).toMap()

        account.unlock()
        recordLegacyEvent(AdminEventType.ACCOUNT_UNLOCKED, actorAdminId, account, request.reason, sourceAddress)
        adminAuditService.recordMutation(
            action = AdminAuditAction.ACCOUNT_UNLOCKED,
            actorAdminId = actorAdminId,
            targetType = AdminAuditTargetType.ADMIN_ACCOUNT,
            targetId = account.requiredId.toString(),
            targetLabel = account.username,
            reason = request.reason,
            sourceAddress = sourceAddress,
            before = before,
            after = AdminAccountAuditSnapshot.from(account).toMap(),
        )
        return AdminAccountResponse.from(account)
    }

    @Transactional
    fun issueTemporaryPassword(
        actorAdminId: Long,
        targetAdminId: Long,
        request: AdminReasonRequest,
        sourceAddress: String?,
    ): AdminTemporaryPasswordResponse {
        validateReason(request.reason)
        if (actorAdminId == targetAdminId) {
            throw AdminException(AdminErrorCode.SELF_TEMPORARY_PASSWORD_FORBIDDEN)
        }
        val account = requireWithLock(targetAdminId)
        val temporaryPassword = secretGenerator.generate()

        account.issueTemporaryPassword(passwordHasher.hash(temporaryPassword), ZonedDateTime.now(clock))
        adminSessionService.revokeAll(targetAdminId)
        recordEvent(
            AdminEventType.TEMPORARY_PASSWORD_ISSUED,
            actorAdminId,
            account,
            request.reason,
            sourceAddress,
        )
        return AdminTemporaryPasswordResponse(
            account = AdminAccountResponse.from(account),
            temporaryPassword = temporaryPassword,
        )
    }

    @Transactional
    fun recoverFromCli(
        username: String,
        displayName: String,
        temporaryPassword: String,
    ): AdminAccountResponse {
        val normalizedUsername = AdminAccount.normalizeUsername(username)
        val now = ZonedDateTime.now(clock)
        val passwordHash = passwordHasher.hash(temporaryPassword)
        val account = adminAccountRepository.findWithLockByUsername(normalizedUsername)
            ?.also { it.recover(passwordHash, now) }
            ?: adminAccountRepository.save(
                AdminAccount.of(
                    username = normalizedUsername,
                    displayName = displayName,
                    passwordHash = passwordHash,
                    now = now,
                    createdByAdminId = null,
                ),
            )

        adminSessionService.revokeAll(account.requiredId)
        recordEvent(
            eventType = AdminEventType.CLI_RECOVERY,
            actorAdminId = null,
            account = account,
            reason = "server CLI recovery",
            sourceAddress = "localhost",
        )
        return AdminAccountResponse.from(account)
    }

    private fun requireWithLock(adminId: Long): AdminAccount =
        adminAccountRepository.findWithLockById(adminId)
            ?: throw AdminException(AdminErrorCode.ACCOUNT_NOT_FOUND)

    private fun validateReason(reason: String) {
        if (reason.isBlank() || reason.trim().length > AdminAuthEvent.REASON_MAX_LENGTH) {
            throw AdminException(AdminErrorCode.INVALID_REASON)
        }
    }

    private fun recordLegacyEvent(
        eventType: AdminEventType,
        actorAdminId: Long?,
        account: AdminAccount,
        reason: String,
        sourceAddress: String?,
    ) {
        adminAuthEventRepository.save(
            AdminAuthEvent.of(
                eventType = eventType,
                actorAdminId = actorAdminId,
                targetAdminId = account.requiredId,
                usernameSnapshot = account.username,
                sourceAddress = sourceAddress,
                reason = reason,
                successful = true,
            ),
        )
    }

    private fun recordEvent(
        eventType: AdminEventType,
        actorAdminId: Long?,
        account: AdminAccount,
        reason: String,
        sourceAddress: String?,
    ) {
        recordLegacyEvent(eventType, actorAdminId, account, reason, sourceAddress)
        adminAuditService.recordEvent(
            action = AdminAuditAction.valueOf(eventType.name),
            outcome = AdminAuditOutcome.SUCCESS,
            actorAdminId = actorAdminId,
            targetType = AdminAuditTargetType.ADMIN_ACCOUNT,
            targetId = account.requiredId.toString(),
            targetLabel = account.username,
            reason = reason,
            sourceAddress = sourceAddress,
            changedFields = when (eventType) {
                AdminEventType.TEMPORARY_PASSWORD_ISSUED,
                AdminEventType.CLI_RECOVERY,
                -> listOf("password", "mustChangePassword", "failedLoginAttempts", "lockedUntil", "status")
                else -> emptyList()
            },
        )
    }
}
