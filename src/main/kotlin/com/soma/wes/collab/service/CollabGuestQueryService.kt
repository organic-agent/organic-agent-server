package com.soma.wes.collab.service

import com.soma.wes.collab.dto.response.CollabCommentResponse
import com.soma.wes.collab.dto.response.CollabLandingResponse
import com.soma.wes.collab.dto.response.CollabPhotoPageResponse
import com.soma.wes.collab.exception.CollabErrorCode
import com.soma.wes.collab.exception.CollabException
import com.soma.wes.collab.repository.CollabParticipantRepository
import com.soma.wes.collab.repository.CollabPhotoCommentRepository
import com.soma.wes.collab.support.CollabPhotoMembership
import com.soma.wes.collab.support.CollabPhotoViewAssembler
import com.soma.wes.collab.support.CollabSessionAccess
import com.soma.wes.global.page.PageRequests
import com.soma.wes.global.page.PageResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CollabGuestQueryService(
    private val sessionAccess: CollabSessionAccess,
    private val sessionRepository: com.soma.wes.collab.repository.CollabSessionRepository,
    private val clock: java.time.Clock,
    private val participantRepository: CollabParticipantRepository,
    private val commentRepository: CollabPhotoCommentRepository,
    private val photoViewAssembler: CollabPhotoViewAssembler,
    private val membership: CollabPhotoMembership,
) {
    @Transactional(readOnly = true)
    fun getLanding(collabToken: String): CollabLandingResponse {
        val access = sessionAccess.requireReadable(collabToken)
        val sessions = (if (access.session.includeAllAlbums) sessionRepository.findAllByGalleryIdOrderByCreatedAtDesc(access.gallery.requiredId)
            else listOf(access.session)).filter { !it.isRevoked && !it.isExpiredAt(java.time.ZonedDateTime.now(clock)) }
        val photosBySession = membership.photoIds(sessions)
        return CollabLandingResponse(
            access.gallery.title,
            photosBySession[access.sessionId].orEmpty().size.toLong(),
            sessionAccess.isWritable(access),
            coverTitle = access.session.coverTitle ?: access.session.name,
            coverAuthor = access.session.coverAuthor,
            expiresAt = access.session.expiresAt,
            albums = sessions.map { session -> CollabLandingResponse.Album(
                conceptFolderId = session.conceptFolderId, name = session.name,
                photoCount = photosBySession[session.requiredId].orEmpty().size.toLong(),
                collabToken = session.collabToken, sessionId = session.requiredId,
            ) },
        )
    }

    @Transactional(readOnly = true)
    fun listPhotos(collabToken: String, userId: Long?, guestToken: String?, page: Int, size: Int): CollabPhotoPageResponse {
        val access = sessionAccess.requireReadable(collabToken)
        return photoViewAssembler.toPage(
            access.session,
            page,
            size,
            sessionAccess.findParticipant(access, userId, guestToken)?.requiredId,
        )
    }

    fun listPhotos(collabToken: String, guestToken: String?, page: Int, size: Int): CollabPhotoPageResponse =
        listPhotos(collabToken, null, guestToken, page, size)

    @Transactional(readOnly = true)
    fun listComments(
        collabToken: String,
        photoId: Long,
        userId: Long?,
        guestToken: String?,
        page: Int,
        size: Int,
    ): PageResponse<CollabCommentResponse> {
        val access = sessionAccess.requireReadable(collabToken)
        if (!photoViewAssembler.contains(access.session, photoId)) {
            throw CollabException(CollabErrorCode.COLLAB_PHOTO_NOT_FOUND)
        }
        val found = commentRepository.findAllByCollabSessionIdAndPhotoIdOrderByIdDesc(
            access.sessionId,
            photoId,
            PageRequests.of(page, size),
        )
        val nicknames = participantRepository.findAllByIdIn(found.content.map { it.participantId })
            .associate { it.requiredId to it.nickname }
        val mine = sessionAccess.findParticipant(access, userId, guestToken)?.requiredId
        return PageResponse.of(found, found.content.map { comment ->
            CollabCommentResponse(
                comment.requiredId,
                nicknames[comment.participantId] ?: "알 수 없음",
                comment.content,
                comment.createdAt,
                mine == comment.participantId,
            )
        })
    }

    fun listComments(
        collabToken: String,
        photoId: Long,
        guestToken: String?,
        page: Int,
        size: Int,
    ): PageResponse<CollabCommentResponse> = listComments(collabToken, photoId, null, guestToken, page, size)
}
