package com.soma.wes.admin.dto.response

import com.soma.wes.admin.domain.AdminAccount
import com.soma.wes.admin.domain.AdminAccountStatus
import java.time.ZonedDateTime

data class AdminAccountResponse(
    val id: Long,
    val version: Long,
    val username: String,
    val displayName: String,
    val status: AdminAccountStatus,
    val mustChangePassword: Boolean,
    val failedLoginAttempts: Int,
    val lockedUntil: ZonedDateTime?,
    val lastLoginAt: ZonedDateTime?,
    val createdAt: ZonedDateTime?,
) {

    companion object {
        fun from(account: AdminAccount): AdminAccountResponse =
            AdminAccountResponse(
                id = account.requiredId,
                version = account.version,
                username = account.username,
                displayName = account.displayName,
                status = account.status,
                mustChangePassword = account.mustChangePassword,
                failedLoginAttempts = account.failedLoginAttempts,
                lockedUntil = account.lockedUntil,
                lastLoginAt = account.lastLoginAt,
                createdAt = account.createdAt,
            )
    }
}
