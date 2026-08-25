package com.soma.wes.admin.audit.support

import com.soma.wes.admin.audit.repository.AdminEntityRevisionRepository
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.ZonedDateTime

@Component
class AdminRevisionPurgeScheduler(
    private val revisionRepository: AdminEntityRevisionRepository,
    private val clock: Clock,
) {

    @Scheduled(cron = "0 15 * * * *")
    @Transactional
    fun purgeExpiredRevisions(): Long =
        revisionRepository.deleteAllByExpiresAtLessThanEqual(ZonedDateTime.now(clock))
}
