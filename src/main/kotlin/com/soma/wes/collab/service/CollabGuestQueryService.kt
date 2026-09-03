package com.soma.wes.collab.service

import com.soma.wes.collab.dto.response.CollabCommentResponse
import com.soma.wes.collab.dto.response.CollabLandingResponse
import com.soma.wes.collab.dto.response.CollabPhotoPageResponse
import com.soma.wes.collab.exception.CollabErrorCode
import com.soma.wes.collab.exception.CollabException
import com.soma.wes.collab.repository.CollabGuestRepository
import com.soma.wes.collab.repository.CollabPhotoCommentRepository
import com.soma.wes.collab.support.CollabPhotoViewAssembler
import com.soma.wes.collab.support.CollabSessionAccess
import com.soma.wes.global.page.PageRequests
import com.soma.wes.global.page.PageResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CollabGuestQueryService(
    private val sessionAccess: CollabSessionAccess,
    private val guestRepository: CollabGuestRepository,
    private val commentRepository: CollabPhotoCommentRepository,
    private val photoViewAssembler: CollabPhotoViewAssembler,
) {
    @Transactional(readOnly = true)
    fun getLanding(collabToken: String): CollabLandingResponse {
        val access = sessionAccess.requireReadable(collabToken)
        return CollabLandingResponse(
            access.gallery.title,
            photoViewAssembler.count(access.session.conceptFolderId),
            sessionAccess.isWritable(access),
        )
    }

    @Transactional(readOnly = true)
    fun listPhotos(collabToken: String, guestToken: String?, page: Int, size: Int): CollabPhotoPageResponse {
        val access = sessionAccess.requireReadable(collabToken)
        return photoViewAssembler.toPage(
            access.sessionId,
            access.session.conceptFolderId,
            page,
            size,
            sessionAccess.findGuest(access, guestToken)?.requiredId,
        )
    }

    @Transactional(readOnly = true)
    fun listComments(
        collabToken: String,
        photoId: Long,
        guestToken: String?,
        page: Int,
        size: Int,
    ): PageResponse<CollabCommentResponse> {
        val access = sessionAccess.requireReadable(collabToken)
        if (!photoViewAssembler.contains(access.session.conceptFolderId, photoId)) {
            throw CollabException(CollabErrorCode.COLLAB_PHOTO_NOT_FOUND)
        }
        val found = commentRepository.findAllByCollabSessionIdAndPhotoIdOrderByIdDesc(
            access.sessionId,
            photoId,
            PageRequests.of(page, size),
        )
        val nicknames = guestRepository.findAllByIdIn(found.content.map { it.collabGuestId })
            .associate { it.requiredId to it.nickname }
        val mine = sessionAccess.findGuest(access, guestToken)?.requiredId
        return PageResponse.of(found, found.content.map { comment ->
            CollabCommentResponse(
                comment.requiredId,
                nicknames[comment.collabGuestId] ?: "알 수 없음",
                comment.content,
                comment.createdAt,
                mine == comment.collabGuestId,
            )
        })
    }
}
