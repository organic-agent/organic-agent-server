package com.soma.wes.admin.service

import com.soma.wes.admin.domain.AdminAccount
import com.soma.wes.admin.domain.AdminAccountStatus
import com.soma.wes.admin.domain.AdminEventType
import com.soma.wes.admin.audit.support.AdminAuditSanitizer
import com.soma.wes.admin.dto.request.ChangeAdminStatusRequest
import com.soma.wes.admin.dto.request.CreateAdminAccountRequest
import com.soma.wes.admin.dto.response.AdminAccountListResponse
import com.soma.wes.admin.dto.response.AdminAccountResponse
import com.soma.wes.admin.dto.response.AdminTemporaryPasswordResponse
import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.repository.AdminAccountRepository
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
    private val auditSanitizer: AdminAuditSanitizer,
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
        val account = requireWithLock(targetAdminId)

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
        return AdminAccountResponse.from(account)
    }

    @Transactional
    fun unlock(
        actorAdminId: Long,
        targetAdminId: Long,
        sourceAddress: String?,
    ): AdminAccountResponse {
        val account = requireWithLock(targetAdminId)

        account.unlock()
        return AdminAccountResponse.from(account)
    }

    @Transactional
    fun issueTemporaryPassword(
        actorAdminId: Long,
        targetAdminId: Long,
        sourceAddress: String?,
    ): AdminTemporaryPasswordResponse {
        if (actorAdminId == targetAdminId) {
            throw AdminException(AdminErrorCode.SELF_TEMPORARY_PASSWORD_FORBIDDEN)
        }
        val account = requireWithLock(targetAdminId)
        val temporaryPassword = secretGenerator.generate()

        account.issueTemporaryPassword(passwordHasher.hash(temporaryPassword), ZonedDateTime.now(clock))
        adminSessionService.revokeAll(targetAdminId)
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
        return AdminAccountResponse.from(account)
    }

    private fun requireWithLock(adminId: Long): AdminAccount =
        adminAccountRepository.findWithLockById(adminId)
            ?: throw AdminException(AdminErrorCode.ACCOUNT_NOT_FOUND)
}
