package com.soma.wes.admin.dto.response

import com.soma.wes.admin.domain.AdminAccount
import java.time.ZonedDateTime

data class AdminSessionResponse(
    val admin: AdminAccountResponse,
    val absoluteExpiresAt: ZonedDateTime,
) {

    companion object {
        fun of(
            account: AdminAccount,
            absoluteExpiresAt: ZonedDateTime,
        ): AdminSessionResponse =
            AdminSessionResponse(
                admin = AdminAccountResponse.from(account),
                absoluteExpiresAt = absoluteExpiresAt,
            )
    }
}
