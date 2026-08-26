package com.soma.wes.admin.impersonation.service

import com.soma.wes.admin.audit.domain.AdminAuditAction
import com.soma.wes.admin.audit.domain.AdminAuditOutcome
import com.soma.wes.admin.audit.domain.AdminAuditTargetType
import com.soma.wes.admin.audit.service.AdminAuditService
import com.soma.wes.admin.domain.AdminLoginUser
import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.impersonation.dto.AdminImpersonationAdminResponse
import com.soma.wes.admin.impersonation.dto.AdminImpersonationResponse
import com.soma.wes.admin.impersonation.dto.StartAdminImpersonationRequest
import com.soma.wes.admin.impersonation.repository.AdminImpersonationRepository
import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.admin.resource.service.AdminResourceContextService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import java.util.UUID

@Service
class AdminImpersonationService(
    private val repository: AdminImpersonationRepository,
    private val resourceContextService: AdminResourceContextService,
    private val auditService: AdminAuditService,
    private val clock: Clock,
) {

    @Transactional
    fun start(
        actor: AdminLoginUser,
        request: StartAdminImpersonationRequest,
        sourceAddress: String?,
    ): AdminImpersonationResponse {
        requireSupportedTarget(request.targetType)
        val context = resourceContextService.get(request.targetType, request.targetId)
        val now = ZonedDateTime.now(clock)
        val session = AdminImpersonationRepository.Session(
            id = UUID.randomUUID(),
            adminId = actor.id,
            targetType = request.targetType,
            targetId = request.targetId,
            targetLabel = context.resource.label,
            reason = request.reason.trim(),
            sourceAddress = sourceAddress,
            startedAt = now,
            expiresAt = now + SESSION_TTL,
        )
        repository.insert(session)
        auditService.recordEvent(
            action = AdminAuditAction.READ_ONLY_IMPERSONATION_STARTED,
            outcome = AdminAuditOutcome.SUCCESS,
            actorAdminId = actor.id,
            actorUsername = actor.username,
            targetType = AdminAuditTargetType.valueOf(request.targetType.name),
            targetId = request.targetId.toString(),
            targetLabel = context.resource.label,
            reason = request.reason.trim(),
            sourceAddress = sourceAddress,
        )
        return session.toResponse(actor, context)
    }

    @Transactional
    fun view(
        actor: AdminLoginUser,
        sessionId: UUID,
        sourceAddress: String?,
    ): AdminImpersonationResponse {
        val session = requireActive(sessionId, actor.id)
        val context = resourceContextService.get(session.targetType, session.targetId)
        auditService.recordEvent(
            action = AdminAuditAction.READ_ONLY_IMPERSONATION_VIEWED,
            outcome = AdminAuditOutcome.SUCCESS,
            actorAdminId = actor.id,
            actorUsername = actor.username,
            targetType = AdminAuditTargetType.valueOf(session.targetType.name),
            targetId = session.targetId.toString(),
            targetLabel = session.targetLabel,
            reason = session.reason,
            sourceAddress = sourceAddress,
        )
        return session.toResponse(actor, context)
    }

    @Transactional
    fun end(actor: AdminLoginUser, sessionId: UUID, sourceAddress: String?) {
        val session = requireActive(sessionId, actor.id)
        if (!repository.end(sessionId, actor.id, ZonedDateTime.now(clock))) {
            throw AdminException(AdminErrorCode.IMPERSONATION_NOT_FOUND)
        }
        auditService.recordEvent(
            action = AdminAuditAction.READ_ONLY_IMPERSONATION_ENDED,
            outcome = AdminAuditOutcome.SUCCESS,
            actorAdminId = actor.id,
            actorUsername = actor.username,
            targetType = AdminAuditTargetType.valueOf(session.targetType.name),
            targetId = session.targetId.toString(),
            targetLabel = session.targetLabel,
            reason = session.reason,
            sourceAddress = sourceAddress,
        )
    }

    @Transactional(readOnly = true)
    fun requireActive(sessionId: UUID, adminId: Long): AdminImpersonationRepository.Session =
        repository.findActive(sessionId, adminId, ZonedDateTime.now(clock))
            ?: throw AdminException(AdminErrorCode.IMPERSONATION_NOT_FOUND)

    private fun requireSupportedTarget(targetType: AdminResourceType) {
        if (targetType !in SUPPORTED_TARGETS) {
            throw AdminException(AdminErrorCode.INVALID_IMPERSONATION_TARGET)
        }
    }

    private fun AdminImpersonationRepository.Session.toResponse(
        actor: AdminLoginUser,
        context: com.soma.wes.admin.resource.dto.AdminResourceContextResponse,
    ) = AdminImpersonationResponse(
        id = id,
        admin = AdminImpersonationAdminResponse(actor.id, actor.username, actor.displayName),
        context = context,
        startedAt = startedAt,
        expiresAt = expiresAt,
    )

    companion object {
        private val SESSION_TTL = Duration.ofMinutes(30)
        private val SUPPORTED_TARGETS = setOf(
            AdminResourceType.USER,
            AdminResourceType.STUDIO,
            AdminResourceType.GALLERY,
        )
    }
}
