package com.soma.wes.admin.audit.service

import com.soma.wes.admin.audit.domain.AdminAuditAction
import com.soma.wes.admin.audit.domain.AdminAuditOutcome
import com.soma.wes.admin.audit.domain.AdminAuditTargetType
import com.soma.wes.admin.domain.AdminLoginUser
import com.soma.wes.admin.resource.domain.AdminResourceType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 관리자 리소스 조회 결과와 분리된 메타데이터 전용 감사 경계다.
 * 호출자가 응답 DTO나 검색어를 넘길 수 없게 해 조회한 필드·이름·PII·secret의 영구 저장을 막는다.
 * 세션 메타데이터는 쿠키/원문 토큰이 없는 서버 내부 surrogate UUID만 사용한다.
 */
@Service
class AdminResourceReadAuditService(
    private val auditService: AdminAuditService,
) {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordList(
        actor: AdminLoginUser,
        sourceAddress: String?,
        page: Int,
        size: Int,
        queryFilterPresent: Boolean,
        typeFilterPresent: Boolean,
        typeFilterCount: Int,
        returnedCount: Int,
        totalCount: Long,
    ) {
        record(
            actor = actor,
            sourceAddress = sourceAddress,
            targetType = AdminAuditTargetType.ADMIN_OPERATION,
            targetId = RESOURCE_LIST_TARGET_ID,
            metadata = listOf(
                "route=RESOURCE_LIST",
                "page=$page",
                "size=$size",
                "queryFilterPresent=$queryFilterPresent",
                "typeFilterPresent=$typeFilterPresent",
                "typeFilterCount=$typeFilterCount",
                "returnedCount=$returnedCount",
                "totalCount=$totalCount",
            ),
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordDetail(
        actor: AdminLoginUser,
        sourceAddress: String?,
        type: AdminResourceType,
        id: Long,
    ) {
        record(
            actor = actor,
            sourceAddress = sourceAddress,
            targetType = type.auditTargetType,
            targetId = id.toString(),
            metadata = listOf("route=RESOURCE_DETAIL"),
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordContext(
        actor: AdminLoginUser,
        sourceAddress: String?,
        type: AdminResourceType,
        id: Long,
    ) {
        record(
            actor = actor,
            sourceAddress = sourceAddress,
            targetType = type.auditTargetType,
            targetId = id.toString(),
            metadata = listOf("route=RESOURCE_CONTEXT"),
        )
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordAnalysisFailures(
        actor: AdminLoginUser,
        sourceAddress: String?,
        galleryId: Long,
    ) {
        record(
            actor = actor,
            sourceAddress = sourceAddress,
            targetType = AdminResourceType.GALLERY.auditTargetType,
            targetId = galleryId.toString(),
            metadata = listOf("route=GALLERY_ANALYSIS_FAILURES"),
        )
    }

    /**
     * 리소스 전용 controller 감사 밖의 관리자 GET과 모든 조회 실패를 기록한다.
     * requestUri 원문이나 query string을 저장하지 않고, 정해진 route와 안전한 숫자/enum ID만 남긴다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun recordRequest(
        actor: AdminLoginUser?,
        sourceAddress: String?,
        requestUri: String,
        status: Int,
    ) {
        val target = classify(requestUri)
        auditService.recordMetadataEvent(
            action = AdminAuditAction.RESOURCE_VIEWED,
            outcome = if (status < 400) AdminAuditOutcome.SUCCESS else AdminAuditOutcome.FAILURE,
            actorAdminId = actor?.id,
            actorUsername = actor?.username,
            targetType = target.targetType,
            targetId = target.targetId,
            targetLabel = null,
            metadata = buildList {
                add("route=${target.route}")
                add("status=$status")
                actor?.let { add("adminSessionId=${it.adminSessionId}") }
            }.joinToString(" "),
            sourceAddress = sourceAddress,
        )
    }

    /** 전용 성공 감사가 이미 생성되는 조회는 성공 시 filter 중복 기록을 생략한다. */
    fun hasDedicatedSuccessAudit(requestUri: String): Boolean =
        requestUri.startsWith(RESOURCES_PATH) || requestUri.startsWith(IMPERSONATIONS_PATH)

    private fun record(
        actor: AdminLoginUser,
        sourceAddress: String?,
        targetType: AdminAuditTargetType,
        targetId: String,
        metadata: List<String>,
    ) {
        auditService.recordMetadataEvent(
            action = AdminAuditAction.RESOURCE_VIEWED,
            outcome = AdminAuditOutcome.SUCCESS,
            actorAdminId = actor.id,
            actorUsername = actor.username,
            targetType = targetType,
            targetId = targetId,
            targetLabel = null,
            metadata = (metadata + "adminSessionId=${actor.adminSessionId}").joinToString(" "),
            sourceAddress = sourceAddress,
        )
    }

    private fun classify(requestUri: String): ReadTarget {
        val relative = requestUri.removePrefix(ADMIN_API_PREFIX).trim('/')
        val segments = relative.split('/').filter(String::isNotBlank)
        if (segments.firstOrNull() == "resources") return classifyResource(segments)
        if (segments.firstOrNull() == "audit-logs") return classifyAudit(segments)
        return when {
            relative == "admins" -> ReadTarget("ADMIN_ACCOUNT_LIST", AdminAuditTargetType.ADMIN_OPERATION, "ADMIN_ACCOUNT_LIST")
            relative == "operations/overview" -> ReadTarget("OPERATIONS_OVERVIEW", AdminAuditTargetType.ADMIN_OPERATION, "OPERATIONS_OVERVIEW")
            relative == "operations/observability-links" -> ReadTarget("OBSERVABILITY_LINKS", AdminAuditTargetType.ADMIN_OPERATION, "OBSERVABILITY_LINKS")
            relative == "operations/trash" -> ReadTarget("TRASH_LIST", AdminAuditTargetType.TRASH_BATCH, "TRASH_LIST")
            relative == "operations/trash/children" -> ReadTarget("CHILD_TRASH_LIST", AdminAuditTargetType.TRASH_BATCH, "CHILD_TRASH_LIST")
            relative == "system-settings" -> ReadTarget("SYSTEM_SETTINGS", AdminAuditTargetType.SYSTEM_SETTING, "SYSTEM_SETTINGS")
            relative == "auth/session" -> ReadTarget("AUTH_SESSION", AdminAuditTargetType.ADMIN_OPERATION, "AUTH_SESSION")
            relative.startsWith("impersonations/") -> ReadTarget("IMPERSONATION_READ", AdminAuditTargetType.IMPERSONATION, "IMPERSONATION_READ")
            else -> ReadTarget("ADMIN_READ", AdminAuditTargetType.ADMIN_OPERATION, "ADMIN_READ")
        }
    }

    private fun classifyResource(segments: List<String>): ReadTarget {
        if (segments.size == 1) {
            return ReadTarget("RESOURCE_LIST", AdminAuditTargetType.ADMIN_OPERATION, RESOURCE_LIST_TARGET_ID)
        }
        val type = segments.getOrNull(1)?.let { runCatching { AdminResourceType.valueOf(it) }.getOrNull() }
        val id = segments.getOrNull(2)?.toLongOrNull()
        if (type == null || id == null) {
            return ReadTarget("RESOURCE_READ", AdminAuditTargetType.ADMIN_OPERATION, "RESOURCE_READ")
        }
        val route = when (segments.getOrNull(3)) {
            "context" -> "RESOURCE_CONTEXT"
            "analysis-failures" -> "GALLERY_ANALYSIS_FAILURES"
            else -> "RESOURCE_DETAIL"
        }
        return ReadTarget(route, type.auditTargetType, id.toString())
    }

    private fun classifyAudit(segments: List<String>): ReadTarget = when {
        segments.size == 1 -> ReadTarget("AUDIT_LOG_LIST", AdminAuditTargetType.ADMIN_OPERATION, "AUDIT_LOG_LIST")
        segments.getOrNull(1) == "targets" -> {
            val type = segments.getOrNull(2)?.let { runCatching { AdminAuditTargetType.valueOf(it) }.getOrNull() }
            val id = segments.getOrNull(3)?.takeIf(SAFE_TARGET_ID::matches)
            if (type != null && id != null) ReadTarget("REVISION_LIST", type, id)
            else ReadTarget("REVISION_LIST", AdminAuditTargetType.ADMIN_OPERATION, "REVISION_LIST")
        }
        segments.getOrNull(1)?.toLongOrNull() != null ->
            ReadTarget("AUDIT_LOG_DETAIL", AdminAuditTargetType.ADMIN_OPERATION, "AUDIT_LOG_DETAIL")
        else -> ReadTarget("AUDIT_LOG_READ", AdminAuditTargetType.ADMIN_OPERATION, "AUDIT_LOG_READ")
    }

    private data class ReadTarget(
        val route: String,
        val targetType: AdminAuditTargetType,
        val targetId: String,
    )

    companion object {
        private const val ADMIN_API_PREFIX = "/internal/admin/v1/"
        private const val RESOURCES_PATH = "${ADMIN_API_PREFIX}resources"
        private const val IMPERSONATIONS_PATH = "${ADMIN_API_PREFIX}impersonations"
        private const val RESOURCE_LIST_TARGET_ID = "RESOURCE_LIST"
        private val SAFE_TARGET_ID = Regex("(?:[0-9]+|[0-9a-fA-F-]{36}|[A-Z][A-Z0-9_.-]{0,63})")
    }
}
