package com.soma.wes.admin.domain

import com.soma.wes.admin.config.AdminAuthProperties
import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.ZonedDateTime

@Entity
@Table(name = "admin_accounts")
class AdminAccount private constructor(

    @Column(name = "username", nullable = false, updatable = false, length = USERNAME_MAX_LENGTH)
    val username: String,

    @Column(name = "display_name", nullable = false, length = DISPLAY_NAME_MAX_LENGTH)
    var displayName: String,

    @Column(name = "password_hash", nullable = false, length = 255)
    var passwordHash: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: AdminAccountStatus,

    @Column(name = "must_change_password", nullable = false)
    var mustChangePassword: Boolean,

    @Column(name = "failed_login_attempts", nullable = false)
    var failedLoginAttempts: Int,

    @Column(name = "locked_until")
    var lockedUntil: ZonedDateTime?,

    @Column(name = "password_changed_at", nullable = false)
    var passwordChangedAt: ZonedDateTime,

    @Column(name = "last_login_at")
    var lastLoginAt: ZonedDateTime?,

    @Column(name = "created_by_admin_id", updatable = false)
    val createdByAdminId: Long?,

) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    val requiredId: Long
        get() = checkNotNull(id) { "저장되지 않은 최고 관리자 계정입니다." }

    fun isSuspended(): Boolean = status == AdminAccountStatus.SUSPENDED

    fun isLocked(now: ZonedDateTime): Boolean = lockedUntil?.isAfter(now) == true

    fun recordFailedLogin(
        now: ZonedDateTime,
        lockout: AdminAuthProperties.Lockout,
    ): Boolean {
        if (lockedUntil != null && !isLocked(now)) {
            unlock()
        }

        failedLoginAttempts += 1
        if (failedLoginAttempts < lockout.maxFailedAttempts) {
            return false
        }

        lockedUntil = now.plus(lockout.duration)
        return true
    }

    fun recordSuccessfulLogin(now: ZonedDateTime) {
        unlock()
        lastLoginAt = now
    }

    fun changePassword(
        newPasswordHash: String,
        now: ZonedDateTime,
    ) {
        passwordHash = newPasswordHash
        passwordChangedAt = now
        mustChangePassword = false
        unlock()
    }

    fun issueTemporaryPassword(
        temporaryPasswordHash: String,
        now: ZonedDateTime,
    ) {
        passwordHash = temporaryPasswordHash
        passwordChangedAt = now
        mustChangePassword = true
        unlock()
    }

    fun suspend() {
        status = AdminAccountStatus.SUSPENDED
    }

    fun activate() {
        status = AdminAccountStatus.ACTIVE
    }

    fun unlock() {
        failedLoginAttempts = 0
        lockedUntil = null
    }

    fun recover(
        temporaryPasswordHash: String,
        now: ZonedDateTime,
    ) {
        activate()
        issueTemporaryPassword(temporaryPasswordHash, now)
    }

    fun restoreAuditableState(
        displayName: String,
        status: AdminAccountStatus,
        failedLoginAttempts: Int,
        lockedUntil: ZonedDateTime?,
    ) {
        validateDisplayName(displayName)
        if (failedLoginAttempts < 0) {
            throw AdminException(AdminErrorCode.REVISION_RESTORE_UNSUPPORTED)
        }
        this.displayName = displayName.trim()
        this.status = status
        this.failedLoginAttempts = failedLoginAttempts
        this.lockedUntil = lockedUntil
    }

    companion object {
        const val USERNAME_MIN_LENGTH = 3
        const val USERNAME_MAX_LENGTH = 64
        const val DISPLAY_NAME_MAX_LENGTH = 50

        private val USERNAME_PATTERN = Regex("^[a-z0-9._-]+$")

        fun of(
            username: String,
            displayName: String,
            passwordHash: String,
            now: ZonedDateTime,
            createdByAdminId: Long?,
        ): AdminAccount {
            val normalizedUsername = normalizeUsername(username)
            validateDisplayName(displayName)
            if (passwordHash.isBlank()) {
                throw AdminException(AdminErrorCode.PASSWORD_POLICY_VIOLATION)
            }

            return AdminAccount(
                username = normalizedUsername,
                displayName = displayName.trim(),
                passwordHash = passwordHash,
                status = AdminAccountStatus.ACTIVE,
                mustChangePassword = true,
                failedLoginAttempts = 0,
                lockedUntil = null,
                passwordChangedAt = now,
                lastLoginAt = null,
                createdByAdminId = createdByAdminId,
            )
        }

        fun normalizeUsername(username: String): String {
            val normalized = username.trim().lowercase()
            if (
                normalized.length !in USERNAME_MIN_LENGTH..USERNAME_MAX_LENGTH ||
                !USERNAME_PATTERN.matches(normalized)
            ) {
                throw AdminException(AdminErrorCode.INVALID_USERNAME)
            }
            return normalized
        }

        private fun validateDisplayName(displayName: String) {
            if (displayName.isBlank() || displayName.trim().length > DISPLAY_NAME_MAX_LENGTH) {
                throw AdminException(AdminErrorCode.INVALID_DISPLAY_NAME)
            }
        }
    }
}
