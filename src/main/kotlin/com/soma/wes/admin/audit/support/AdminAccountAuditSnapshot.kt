package com.soma.wes.admin.audit.support

import com.soma.wes.admin.domain.AdminAccount
import com.soma.wes.admin.domain.AdminAccountStatus
import tools.jackson.databind.JsonNode
import java.time.ZonedDateTime

data class AdminAccountAuditSnapshot(
    val username: String,
    val displayName: String,
    val status: AdminAccountStatus,
    val failedLoginAttempts: Int,
    val lockedUntil: ZonedDateTime?,
) {

    fun toMap(): Map<String, Any?> = linkedMapOf(
        "username" to username,
        "displayName" to displayName,
        "status" to status.name,
        "failedLoginAttempts" to failedLoginAttempts,
        "lockedUntil" to lockedUntil?.toString(),
    )

    companion object {
        fun from(account: AdminAccount): AdminAccountAuditSnapshot =
            AdminAccountAuditSnapshot(
                username = account.username,
                displayName = account.displayName,
                status = account.status,
                failedLoginAttempts = account.failedLoginAttempts,
                lockedUntil = account.lockedUntil,
            )

        fun from(node: JsonNode): AdminAccountAuditSnapshot =
            AdminAccountAuditSnapshot(
                username = node.get("username").stringValue(),
                displayName = node.get("displayName").stringValue(),
                status = AdminAccountStatus.valueOf(node.get("status").stringValue()),
                failedLoginAttempts = node.get("failedLoginAttempts").intValue(),
                lockedUntil = node.get("lockedUntil")
                    ?.takeUnless(JsonNode::isNull)
                    ?.stringValue()
                    ?.let(ZonedDateTime::parse),
            )
    }
}
