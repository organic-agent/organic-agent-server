package com.soma.wes.admin.impersonation.service

import com.soma.wes.admin.domain.AdminLoginUser
import com.soma.wes.admin.exception.AdminErrorCode
import com.soma.wes.admin.exception.AdminException
import com.soma.wes.admin.impersonation.dto.AdminImpersonationAdminResponse
import com.soma.wes.admin.impersonation.dto.AdminImpersonationResponse
import com.soma.wes.admin.impersonation.dto.AdminImpersonationViewerResponse
import com.soma.wes.admin.impersonation.dto.StartAdminImpersonationRequest
import com.soma.wes.admin.impersonation.repository.AdminImpersonationRepository
import com.soma.wes.admin.impersonation.repository.AdminImpersonationViewRepository
import com.soma.wes.admin.resource.domain.AdminResourceType
import com.soma.wes.global.filter.HttpLoggingFilter
import org.slf4j.MDC
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import java.util.UUID

@Service
class AdminImpersonationService(
    private val repository: AdminImpersonationRepository,
    private val viewRepository: AdminImpersonationViewRepository,
    private val readModelService: AdminImpersonationReadModelService,
    private val expiryService: AdminImpersonationExpiryService,
    private val clock: Clock,
) {

    @Transactional
    fun start(
        actor: AdminLoginUser,
        request: StartAdminImpersonationRequest,
        sourceAddress: String?,
    ): AdminImpersonationResponse {
        requireSupportedTarget(request.targetType)
        val now = ZonedDateTime.now(clock)
        expiryService.closeExpired(actor.adminSessionId, now)
        if (repository.findCurrent(actor.adminSessionId, now) != null) {
            throw AdminException(AdminErrorCode.IMPERSONATION_ALREADY_ACTIVE)
        }

        val viewer = viewRepository.resolveViewer(request.targetType, request.targetId, request.viewerUserId)
        val sessionId = UUID.randomUUID()
        val session = AdminImpersonationRepository.Session(
            id = sessionId,
            adminId = actor.id,
            adminSessionId = actor.adminSessionId,
            viewerUserId = viewer.userId,
            viewerAccessRole = viewer.accessRole,
            targetType = request.targetType,
            targetId = request.targetId,
            targetLabel = "${request.targetType} #${request.targetId}",

            // IP는 요청 trace와 운영 로그로만 추적하고 장기 세션 행에는 저장하지 않는다.
            sourceAddress = null,
            correlationId = currentRequestCorrelationId(),
            startedAt = now,
            expiresAt = now + SESSION_TTL,
        )
        try {
            repository.insert(session)
        } catch (_: DataIntegrityViolationException) {
            throw AdminException(AdminErrorCode.IMPERSONATION_ALREADY_ACTIVE)
        }
        return session.toResponse(actor)
    }

    @Transactional
    fun current(actor: AdminLoginUser, sourceAddress: String?): AdminImpersonationResponse {
        val session = requireCurrent(actor.adminSessionId)
        return session.toResponse(actor)
    }

    @Transactional
    fun view(actor: AdminLoginUser, sessionId: UUID, sourceAddress: String?): AdminImpersonationResponse {
        val session = requireActive(sessionId, actor.adminSessionId)
        return session.toResponse(actor)
    }

    @Transactional
    fun endCurrent(actor: AdminLoginUser, sourceAddress: String?) {
        val session = requireCurrent(actor.adminSessionId)
        endSession(actor, session, sourceAddress)
    }

    @Transactional
    fun endCurrentIfPresent(actor: AdminLoginUser, sourceAddress: String?): Boolean {
        val session = findCurrent(actor.adminSessionId) ?: return false
        endSession(actor, session, sourceAddress)
        return true
    }

    @Transactional
    fun end(actor: AdminLoginUser, sessionId: UUID, sourceAddress: String?) {
        val session = requireActive(sessionId, actor.adminSessionId)
        endSession(actor, session, sourceAddress)
    }

    /** Security filter용 조회. 로그인 세션 결속을 확인한다. */
    @Transactional(readOnly = true)
    fun findCurrent(adminSessionId: UUID): AdminImpersonationRepository.Session? {
        val now = ZonedDateTime.now(clock)
        expiryService.closeExpired(adminSessionId, now)
        return repository.findCurrent(adminSessionId, now)
    }

    @Transactional(readOnly = true)
    fun requireCurrent(adminSessionId: UUID): AdminImpersonationRepository.Session =
        findCurrent(adminSessionId) ?: throw AdminException(AdminErrorCode.IMPERSONATION_NOT_FOUND)

    @Transactional(readOnly = true)
    fun requireActive(sessionId: UUID, adminSessionId: UUID): AdminImpersonationRepository.Session {
        val now = ZonedDateTime.now(clock)
        expiryService.closeExpired(adminSessionId, now)
        return repository.findActive(sessionId, adminSessionId, now)
            ?: throw AdminException(AdminErrorCode.IMPERSONATION_NOT_FOUND)
    }

    private fun endSession(
        actor: AdminLoginUser,
        session: AdminImpersonationRepository.Session,
        sourceAddress: String?,
    ) {
        if (!repository.end(session.id, actor.adminSessionId, ZonedDateTime.now(clock))) {
            throw AdminException(AdminErrorCode.IMPERSONATION_NOT_FOUND)
        }
    }

    private fun AdminImpersonationRepository.Session.toResponse(actor: AdminLoginUser): AdminImpersonationResponse {
        val viewer = AdminImpersonationViewRepository.Viewer(viewerUserId, viewerAccessRole)
        return AdminImpersonationResponse(
            id = id,
            correlationId = currentRequestCorrelationId(),
            admin = AdminImpersonationAdminResponse(actor.id, actor.username, actor.displayName),
            targetType = targetType,
            targetId = targetId,
            viewer = AdminImpersonationViewerResponse(viewerUserId, viewerAccessRole),
            view = readModelService.view(targetType, targetId, viewer),
            startedAt = startedAt,
            expiresAt = expiresAt,
        )
    }

    private fun currentRequestCorrelationId(): String? =
        MDC.get(HttpLoggingFilter.TRACE_ID_KEY)?.takeIf(REQUEST_TRACE::matches)

    private fun requireSupportedTarget(targetType: AdminResourceType) {
        if (targetType !in SUPPORTED_TARGETS) throw AdminException(AdminErrorCode.INVALID_IMPERSONATION_TARGET)
    }

    companion object {
        private val REQUEST_TRACE = Regex("[0-9a-f]{16}")
        private val SESSION_TTL = Duration.ofMinutes(30)
        private val SUPPORTED_TARGETS = setOf(
            AdminResourceType.USER,
            AdminResourceType.STUDIO,
            AdminResourceType.GALLERY,
        )
    }
}
