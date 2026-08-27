package com.soma.wes.admin.audit.service

import com.soma.wes.admin.audit.domain.AdminAuditAction
import com.soma.wes.admin.audit.domain.AdminAuditLog
import com.soma.wes.admin.audit.domain.AdminAuditOutcome
import com.soma.wes.admin.audit.domain.AdminAuditTargetType
import com.soma.wes.admin.audit.dto.response.AdminAuditLogDetailResponse
import com.soma.wes.admin.audit.dto.response.AdminAuditLogResponse
import com.soma.wes.admin.audit.dto.response.AdminEntityRevisionResponse
import com.soma.wes.admin.audit.repository.AdminAuditLogRepository
import com.soma.wes.admin.audit.repository.AdminEntityRevisionRepository
import com.soma.wes.admin.audit.support.AdminAuditSnapshotCodec
import com.soma.wes.admin.audit.support.AdminAuditSanitizer
import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.global.page.PageRequests
import com.soma.wes.global.page.PageResponse
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.ZonedDateTime
import java.util.UUID

@Service
class AdminAuditQueryService(
    private val auditLogRepository: AdminAuditLogRepository,
    private val revisionRepository: AdminEntityRevisionRepository,
    private val snapshotCodec: AdminAuditSnapshotCodec,
    private val sanitizer: AdminAuditSanitizer,
    private val restorePolicy: AdminRevisionRestorePolicy,
) {

    @Transactional(readOnly = true)
    fun search(
        actorAdminId: Long?,
        actorUsername: String?,
        targetType: AdminAuditTargetType?,
        targetId: String?,
        action: AdminAuditAction?,
        outcome: AdminAuditOutcome?,
        correlationId: String?,
        impersonationSessionId: UUID?,
        from: ZonedDateTime?,
        to: ZonedDateTime?,
        page: Int,
        size: Int,
    ): PageResponse<AdminAuditLogResponse> {
        if (from != null && to != null && from.isAfter(to)) {
            throw AdminException(AdminErrorCode.INVALID_AUDIT_RANGE)
        }
        var specification = Specification<AdminAuditLog> { _, _, criteriaBuilder -> criteriaBuilder.conjunction() }
        actorAdminId?.let { value ->
            specification = specification.and { root, _, criteriaBuilder ->
                criteriaBuilder.equal(root.get<Long>("actorAdminId"), value)
            }
        }
        actorUsername?.trim()?.takeIf(String::isNotBlank)?.lowercase()?.let { value ->
            specification = specification.and { root, _, criteriaBuilder ->
                criteriaBuilder.like(criteriaBuilder.lower(root.get("actorUsernameSnapshot")), "%$value%")
            }
        }
        targetType?.let { value ->
            specification = specification.and { root, _, criteriaBuilder ->
                criteriaBuilder.equal(root.get<AdminAuditTargetType>("targetType"), value)
            }
        }
        targetId?.trim()?.takeIf(String::isNotBlank)?.let { value ->
            specification = specification.and { root, _, criteriaBuilder ->
                criteriaBuilder.equal(root.get<String>("targetId"), value)
            }
        }
        action?.let { value ->
            specification = specification.and { root, _, criteriaBuilder ->
                criteriaBuilder.equal(root.get<AdminAuditAction>("action"), value)
            }
        }
        outcome?.let { value ->
            specification = specification.and { root, _, criteriaBuilder ->
                criteriaBuilder.equal(root.get<AdminAuditOutcome>("outcome"), value)
            }
        }
        correlationId?.trim()?.takeIf(String::isNotBlank)?.let { value ->
            specification = specification.and { root, _, criteriaBuilder ->
                criteriaBuilder.equal(root.get<String>("correlationId"), value)
            }
        }
        impersonationSessionId?.let { value ->
            specification = specification.and { root, _, criteriaBuilder ->
                criteriaBuilder.equal(root.get<UUID>("impersonationSessionId"), value)
            }
        }
        from?.let { value ->
            specification = specification.and { root, _, criteriaBuilder ->
                criteriaBuilder.greaterThanOrEqualTo(root.get("createdAt"), value)
            }
        }
        to?.let { value ->
            specification = specification.and { root, _, criteriaBuilder ->
                criteriaBuilder.lessThanOrEqualTo(root.get("createdAt"), value)
            }
        }
        val found = auditLogRepository.findAll(
            specification,
            PageRequests.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")),
        )
        return PageResponse.of(
            found,
            found.content.map { AdminAuditLogResponse.from(it, sanitizer, snapshotCodec) },
        )
    }

    @Transactional(readOnly = true)
    fun getDetail(auditLogId: Long): AdminAuditLogDetailResponse {
        val audit = auditLogRepository.findById(auditLogId).orElseThrow {
            AdminException(AdminErrorCode.AUDIT_LOG_NOT_FOUND)
        }
        val revision = if (audit.targetType != null && audit.targetId != null && audit.revisionNumber != null) {
            revisionRepository.findByTargetTypeAndTargetIdAndRevisionNumber(
                audit.targetType!!,
                audit.targetId!!,
                audit.revisionNumber!!,
            )
        } else {
            null
        }
        return AdminAuditLogDetailResponse(
            audit = AdminAuditLogResponse.from(audit, sanitizer, snapshotCodec),
            revision = revision?.let {
                AdminEntityRevisionResponse.from(it, snapshotCodec, restorePolicy.isRestorable(it))
            },
        )
    }

    @Transactional(readOnly = true)
    fun getRevisions(targetType: AdminAuditTargetType, targetId: String): List<AdminEntityRevisionResponse> =
        revisionRepository.findAllByTargetTypeAndTargetIdOrderByRevisionNumberDesc(targetType, targetId)
            .map { AdminEntityRevisionResponse.from(it, snapshotCodec, restorePolicy.isRestorable(it)) }
}
