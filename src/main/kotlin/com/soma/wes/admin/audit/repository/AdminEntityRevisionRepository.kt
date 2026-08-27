package com.soma.wes.admin.audit.repository

import com.soma.wes.admin.audit.domain.AdminAuditTargetType
import com.soma.wes.admin.audit.domain.AdminEntityRevision
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.jpa.repository.Modifying
import java.time.ZonedDateTime

interface AdminEntityRevisionRepository : JpaRepository<AdminEntityRevision, Long> {

    @Query(
        """
        SELECT COALESCE(MAX(revision.revisionNumber), 0)
        FROM AdminEntityRevision revision
        WHERE revision.targetType = :targetType
          AND revision.targetId = :targetId
        """,
    )
    fun findMaxRevisionNumber(targetType: AdminAuditTargetType, targetId: String): Long

    fun findByTargetTypeAndTargetIdAndRevisionNumber(
        targetType: AdminAuditTargetType,
        targetId: String,
        revisionNumber: Long,
    ): AdminEntityRevision?

    fun findAllByTargetTypeAndTargetIdOrderByRevisionNumberDesc(
        targetType: AdminAuditTargetType,
        targetId: String,
    ): List<AdminEntityRevision>

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        UPDATE AdminEntityRevision revision
        SET revision.beforeRestorePayload = NULL,
            revision.afterRestorePayload = NULL
        WHERE revision.restoreExpiresAt <= :now
          AND (revision.beforeRestorePayload IS NOT NULL OR revision.afterRestorePayload IS NOT NULL)
        """,
    )
    fun clearExpiredRestorePayloads(now: ZonedDateTime): Int
}
