package com.soma.wes.admin.impersonation.service

import com.soma.wes.admin.impersonation.repository.AdminImpersonationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.ZonedDateTime
import java.util.UUID

/**
 * 만료 세션 종료를 별도 트랜잭션으로 확정한다. 호출자가 이후 404를 던져도
 * 이 트랜잭션은 롤백되지 않으며, 종료 상태가 유지된다.
 */
@Service
class AdminImpersonationExpiryService(
    private val repository: AdminImpersonationRepository,
) {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun closeExpired(adminSessionId: UUID, now: ZonedDateTime): Int {
        val expired = repository.closeExpired(adminSessionId, now)
        return expired.size
    }
}
