package com.soma.wes.admin.dto.response

import com.soma.wes.admin.domain.AdminAccount
import com.soma.wes.admin.domain.AdminAccountStatus
import io.swagger.v3.oas.annotations.media.Schema
import java.time.ZonedDateTime

data class AdminAccountResponse(
    val id: Long,
    val version: Long,
    val username: String,
    val displayName: String,
    val status: AdminAccountStatus,
    val mustChangePassword: Boolean,
    val failedLoginAttempts: Int,
    @field:Schema(
        description = "로그인 잠금이 풀리는 시각. 연속 실패가 상한에 닿으면 채워지고, 잠긴 적이 없거나 로그인 성공·비밀번호 변경·임시 비밀번호 발급으로 풀리면 null이다. 지난 시각이면 이미 풀린 상태다.",
        example = "2026-11-04T10:30:00+09:00",
    )
    val lockedUntil: ZonedDateTime?,
    @field:Schema(
        description = "마지막 로그인 성공 시각. 한 번도 로그인하지 않았으면 null이다.",
        example = "2026-11-04T09:00:00+09:00",
    )
    val lastLoginAt: ZonedDateTime?,
    @field:Schema(
        description = "계정 생성 시각. 저장할 때 자동으로 기록되며 컬럼이 nullable이라 기록이 없으면 null이다.",
        example = "2026-10-01T12:00:00+09:00",
    )
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
