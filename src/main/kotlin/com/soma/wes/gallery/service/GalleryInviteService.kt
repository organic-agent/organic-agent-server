package com.soma.wes.gallery.service

import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.domain.GalleryInvite
import com.soma.wes.gallery.domain.GalleryInviteKind
import com.soma.wes.gallery.domain.GalleryInviteStatus
import com.soma.wes.gallery.domain.GalleryMember
import com.soma.wes.gallery.dto.request.IssueGalleryInviteRequest
import com.soma.wes.gallery.dto.response.GalleryInviteAcceptResponse
import com.soma.wes.gallery.dto.response.GalleryInvitePreviewResponse
import com.soma.wes.gallery.dto.response.GalleryInviteResponse
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.repository.GalleryInviteRepository
import com.soma.wes.gallery.repository.GalleryMemberRepository
import com.soma.wes.gallery.repository.GalleryRepository
import com.soma.wes.gallery.repository.requireByIdAndGalleryId
import com.soma.wes.gallery.repository.requireById
import com.soma.wes.gallery.repository.requireWithLockById
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.gallery.support.GalleryInviteUrlResolver
import com.soma.wes.global.SecureTokenGenerator
import com.soma.wes.studio.repository.StudioRepository
import com.soma.wes.workspace.domain.Workspace
import com.soma.wes.workspace.domain.WorkspaceMember
import com.soma.wes.workspace.domain.WorkspaceRole
import com.soma.wes.workspace.domain.WorkspaceType
import com.soma.wes.workspace.repository.WorkspaceMemberRepository
import com.soma.wes.workspace.repository.WorkspaceRepository
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class GalleryInviteService(
    private val galleryRepository: GalleryRepository,
    private val galleryInviteRepository: GalleryInviteRepository,
    private val galleryMemberRepository: GalleryMemberRepository,
    private val workspaceRepository: WorkspaceRepository,
    private val workspaceMemberRepository: WorkspaceMemberRepository,
    private val studioRepository: StudioRepository,
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val tokenGenerator: SecureTokenGenerator,
    private val urlResolver: GalleryInviteUrlResolver,
    private val clock: Clock,
) {
    companion object {
        val VALIDITY: Duration = Duration.ofDays(7)
    }

    @Transactional
    fun issue(
        galleryId: Long,
        userId: Long,
        request: IssueGalleryInviteRequest = IssueGalleryInviteRequest(),
    ): GalleryInviteResponse {
        galleryAccessPolicy.requireManager(galleryId, userId)
        val gallery = galleryRepository.requireWithLockById(galleryId)
        val workspace = workspaceRepository.findById(gallery.workspaceId).orElseThrow {
            GalleryException(GalleryErrorCode.GALLERY_NOT_FOUND)
        }
        validateKind(request.kind, workspace)
        if (request.maxUses !in 1..100) {
            throw GalleryException(GalleryErrorCode.INVALID_INVITE_MAX_USES)
        }

        val now = ZonedDateTime.now(clock)
        val expiresAt = request.expiresAt ?: now.plus(VALIDITY)
        if (!expiresAt.isAfter(now)) {
            throw GalleryException(GalleryErrorCode.INVALID_INVITE_EXPIRY)
        }
        galleryInviteRepository.findByGalleryIdAndRevokedAtIsNull(galleryId)?.let { previous ->
            previous.revoke(now)
            galleryInviteRepository.flush()
        }

        val invite = galleryInviteRepository.save(
            GalleryInvite(
                galleryId = galleryId,
                token = tokenGenerator.generate(),
                kind = request.kind,
                maxUses = request.maxUses,
                expiresAt = expiresAt,
            ),
        )
        return toResponse(invite, now)
    }

    @Transactional(readOnly = true)
    fun getCurrent(galleryId: Long, userId: Long): GalleryInviteResponse {
        galleryAccessPolicy.requireManager(galleryId, userId)
        val invite = galleryInviteRepository.findByGalleryIdAndRevokedAtIsNull(galleryId)
            ?: throw GalleryException(GalleryErrorCode.INVITE_NOT_FOUND)
        return toResponse(invite, ZonedDateTime.now(clock))
    }

    @Transactional(readOnly = true)
    fun preview(token: String, userId: Long): GalleryInvitePreviewResponse {
        val invite = galleryInviteRepository.findByToken(token)
            ?: throw GalleryException(GalleryErrorCode.INVITE_INVALID)
        val gallery = galleryRepository.requireById(invite.galleryId)
        val workspace = workspaceRepository.findById(gallery.workspaceId).orElseThrow {
            GalleryException(GalleryErrorCode.INVITE_INVALID)
        }
        val now = ZonedDateTime.now(clock)
        val status = when {
            invite.isRevoked -> GalleryInviteStatus.REVOKED
            invite.isExpiredAt(now) -> GalleryInviteStatus.EXPIRED
            isAlreadyMember(invite, gallery, userId) -> GalleryInviteStatus.ALREADY_MEMBER
            invite.isFull || isGalleryCapacityFull(invite) -> GalleryInviteStatus.FULL
            else -> GalleryInviteStatus.ACTIVE
        }
        return GalleryInvitePreviewResponse(
            kind = invite.kind,
            status = status,
            workspaceId = workspace.requiredId,
            studioName = studioRepository.findById(workspace.requiredId).orElse(null)?.name,
            galleryId = gallery.requiredId,
            galleryTitle = gallery.title,
            maxUses = invite.maxUses,
            usedCount = invite.usedCount,
            remainingUses = (invite.maxUses - invite.usedCount).coerceAtLeast(0),
            expiresAt = invite.expiresAt,
        )
    }

    private fun toResponse(invite: GalleryInvite, at: ZonedDateTime): GalleryInviteResponse =
        GalleryInviteResponse.of(invite, urlResolver.resolve(invite.token), at)

    @Transactional
    fun revoke(galleryId: Long, inviteId: Long, userId: Long) {
        galleryAccessPolicy.requireManager(galleryId, userId)
        galleryInviteRepository.requireByIdAndGalleryId(inviteId, galleryId)
            .revoke(ZonedDateTime.now(clock))
    }

    @Transactional
    fun accept(token: String, userId: Long): GalleryInviteAcceptResponse {
        val invite = galleryInviteRepository.findWithLockByToken(token)
            ?: throw GalleryException(GalleryErrorCode.INVITE_INVALID)
        val now = ZonedDateTime.now(clock)
        if (invite.isRevoked) throw GalleryException(GalleryErrorCode.INVITE_REVOKED)
        if (invite.isExpiredAt(now)) throw GalleryException(GalleryErrorCode.INVITE_EXPIRED)

        val gallery = galleryRepository.requireWithLockById(invite.galleryId)
        existingMembershipResponse(invite, gallery, userId)?.let { return it }
        galleryAccessPolicy.requireNotManager(invite.galleryId, userId)
        if (invite.isFull || isGalleryCapacityFull(invite)) {
            throw GalleryException(GalleryErrorCode.INVITE_FULL)
        }

        val response = when (invite.kind) {
            GalleryInviteKind.GALLERY_MEMBER -> {
                val member = galleryMemberRepository.save(GalleryMember(gallery.requiredId, userId))
                GalleryInviteAcceptResponse.from(member, gallery.workspaceId, invite.kind)
            }
            GalleryInviteKind.STUDIO_MEMBER,
            GalleryInviteKind.PERSONAL_PARTNER,
            -> {
                workspaceMemberRepository.save(
                    WorkspaceMember(gallery.workspaceId, userId, WorkspaceRole.MEMBER),
                )
                GalleryInviteAcceptResponse.workspace(gallery.requiredId, gallery.workspaceId, invite.kind)
            }
        }
        invite.consume()
        return response
    }

    private fun existingMembershipResponse(
        invite: GalleryInvite,
        gallery: Gallery,
        userId: Long,
    ): GalleryInviteAcceptResponse? = when (invite.kind) {
        GalleryInviteKind.GALLERY_MEMBER -> galleryMemberRepository
            .findByGalleryIdAndUserId(gallery.requiredId, userId)
            ?.let { GalleryInviteAcceptResponse.from(it, gallery.workspaceId, invite.kind) }
        GalleryInviteKind.STUDIO_MEMBER,
        GalleryInviteKind.PERSONAL_PARTNER,
        -> workspaceMemberRepository.findByWorkspaceIdAndUserId(gallery.workspaceId, userId)
            ?.let { GalleryInviteAcceptResponse.workspace(gallery.requiredId, gallery.workspaceId, invite.kind) }
    }

    private fun isAlreadyMember(invite: GalleryInvite, gallery: Gallery, userId: Long): Boolean =
        existingMembershipResponse(invite, gallery, userId) != null

    private fun isGalleryCapacityFull(invite: GalleryInvite): Boolean =
        invite.kind == GalleryInviteKind.GALLERY_MEMBER &&
            galleryMemberRepository.countByGalleryId(invite.galleryId) >= GalleryMember.MAX_PER_GALLERY

    private fun validateKind(kind: GalleryInviteKind, workspace: Workspace) {
        val valid = when (kind) {
            GalleryInviteKind.STUDIO_MEMBER -> workspace.type == WorkspaceType.STUDIO
            GalleryInviteKind.PERSONAL_PARTNER -> workspace.type == WorkspaceType.PERSONAL
            GalleryInviteKind.GALLERY_MEMBER -> true
        }
        if (!valid) throw GalleryException(GalleryErrorCode.INVALID_INVITE_KIND)
    }
}
