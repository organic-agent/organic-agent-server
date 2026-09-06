package com.soma.wes.studio.service

import com.soma.wes.gallery.domain.GalleryInviteKind
import com.soma.wes.gallery.domain.GalleryInviteStatus
import com.soma.wes.gallery.dto.response.GalleryInviteAcceptResponse
import com.soma.wes.gallery.dto.response.GalleryInvitePreviewResponse
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.support.GalleryInviteUrlResolver
import com.soma.wes.global.SecureTokenGenerator
import com.soma.wes.notification.domain.UserNotificationScope
import com.soma.wes.notification.domain.UserNotificationType
import com.soma.wes.notification.service.UserNotificationPublisher
import com.soma.wes.studio.domain.StudioInvite
import com.soma.wes.studio.dto.response.StudioInviteResponse
import com.soma.wes.studio.exception.StudioErrorCode
import com.soma.wes.studio.exception.StudioException
import com.soma.wes.studio.repository.StudioInviteRepository
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.workspace.domain.WorkspaceMember
import com.soma.wes.workspace.domain.WorkspaceRole
import com.soma.wes.workspace.repository.WorkspaceMemberRepository
import com.soma.wes.workspace.repository.WorkspaceRepository
import java.time.Clock
import java.time.ZonedDateTime
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class StudioInviteService(
    private val studioRepository: StudioRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val memberRepository: WorkspaceMemberRepository,
    private val inviteRepository: StudioInviteRepository,
    private val tokenGenerator: SecureTokenGenerator,
    private val urlResolver: GalleryInviteUrlResolver,
    private val notificationPublisher: UserNotificationPublisher,
    private val clock: Clock,
) {
    @Transactional
    fun issue(workspaceId: Long, userId: Long): StudioInviteResponse {
        requireMember(workspaceId, userId)
        workspaceRepository.findWithLockById(workspaceId)
            ?: throw StudioException(StudioErrorCode.STUDIO_NOT_FOUND)
        val now = ZonedDateTime.now(clock)
        inviteRepository.findByWorkspaceIdAndRevokedAtIsNull(workspaceId)?.let {
            it.revokedAt = now
            inviteRepository.flush()
        }
        val invite = inviteRepository.save(StudioInvite(
            workspaceId = workspaceId,
            token = tokenGenerator.generate(),
            expiresAt = now.plusDays(VALID_DAYS),
        ))
        return response(invite, now)
    }

    @Transactional(readOnly = true)
    fun current(workspaceId: Long, userId: Long): StudioInviteResponse {
        requireMember(workspaceId, userId)
        val invite = inviteRepository.findByWorkspaceIdAndRevokedAtIsNull(workspaceId)
            ?: throw GalleryException(GalleryErrorCode.INVITE_NOT_FOUND)
        return response(invite, ZonedDateTime.now(clock))
    }

    private fun requireMember(workspaceId: Long, userId: Long) {
        if (!studioRepository.existsByIdAndSuspendedAtIsNull(workspaceId) ||
            memberRepository.findByWorkspaceIdAndUserId(workspaceId, userId) == null
        ) throw StudioException(StudioErrorCode.STUDIO_ACCESS_DENIED)
    }

    private fun response(invite: StudioInvite, at: ZonedDateTime) = StudioInviteResponse(
        id = invite.requiredId,
        workspaceId = invite.workspaceId,
        inviteUrl = urlResolver.resolve(invite.token),
        status = status(invite, at),
        usedCount = invite.usedCount,
        expiresAt = invite.expiresAt,
        revokedAt = invite.revokedAt,
    )

    private fun status(invite: StudioInvite, at: ZonedDateTime): GalleryInviteStatus = when {
        invite.revokedAt != null -> GalleryInviteStatus.REVOKED
        !invite.expiresAt.isAfter(at) -> GalleryInviteStatus.EXPIRED
        else -> GalleryInviteStatus.ACTIVE
    }

    @Transactional(readOnly = true)
    fun previewIfPresent(token: String, userId: Long): GalleryInvitePreviewResponse? {
        val invite = inviteRepository.findByToken(token) ?: return null
        val studio = studioRepository.findById(invite.workspaceId).orElse(null)
            ?.takeIf { it.suspendedAt == null } ?: throw GalleryException(GalleryErrorCode.INVITE_INVALID)
        val status = status(invite, ZonedDateTime.now(clock))
        return GalleryInvitePreviewResponse(
            kind = GalleryInviteKind.STUDIO_MEMBER,
            status = if (status == GalleryInviteStatus.ACTIVE &&
                memberRepository.findByWorkspaceIdAndUserId(invite.workspaceId, userId) != null
            ) GalleryInviteStatus.ALREADY_MEMBER else status,
            workspaceId = invite.workspaceId,
            studioName = studio.name,
            galleryId = null,
            galleryTitle = null,
            maxUses = null,
            usedCount = invite.usedCount,
            remainingUses = null,
            expiresAt = invite.expiresAt,
        )
    }

    @Transactional
    fun acceptIfPresent(token: String, userId: Long): GalleryInviteAcceptResponse? {
        val found = inviteRepository.findByToken(token) ?: return null
        workspaceRepository.findWithLockById(found.workspaceId)
            ?: throw GalleryException(GalleryErrorCode.INVITE_INVALID)
        val invite = inviteRepository.findWithLockByToken(token)
            ?: throw GalleryException(GalleryErrorCode.INVITE_INVALID)
        when (status(invite, ZonedDateTime.now(clock))) {
            GalleryInviteStatus.REVOKED -> throw GalleryException(GalleryErrorCode.INVITE_REVOKED)
            GalleryInviteStatus.EXPIRED -> throw GalleryException(GalleryErrorCode.INVITE_EXPIRED)
            else -> Unit
        }
        if (!studioRepository.existsByIdAndSuspendedAtIsNull(invite.workspaceId)) {
            throw GalleryException(GalleryErrorCode.INVITE_INVALID)
        }
        val existing = memberRepository.findByWorkspaceIdAndUserId(invite.workspaceId, userId)
        if (existing == null) {
            val recipients = memberRepository.findAllByWorkspaceId(invite.workspaceId).map { it.userId }
            memberRepository.save(WorkspaceMember(
                workspaceId = invite.workspaceId,
                userId = userId,
                role = WorkspaceRole.MEMBER,
            ))
            invite.usedCount += 1
            notificationPublisher.publish(
                userIds = recipients,
                type = UserNotificationType.INVITE_ACCEPTED,
                scope = UserNotificationScope.STUDIO,
                scopeId = invite.workspaceId,
                title = "작가가 합류했습니다",
                message = "초대한 작가가 스튜디오에 합류했습니다.",
            )
        }
        return GalleryInviteAcceptResponse.workspace(null, invite.workspaceId, GalleryInviteKind.STUDIO_MEMBER)
    }

    companion object {
        /** 와이어프레임에서 확정한 초대 유효 기간이다. */
        const val VALID_DAYS = 7L
    }
}
