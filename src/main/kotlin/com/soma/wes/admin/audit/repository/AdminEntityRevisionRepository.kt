package com.soma.wes.admin.audit.repository

import com.soma.wes.admin.audit.domain.AdminAuditTargetType
import com.soma.wes.admin.audit.domain.AdminEntityRevision
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
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

    fun deleteAllByExpiresAtLessThanEqual(expiresAt: ZonedDateTime): Long
}
