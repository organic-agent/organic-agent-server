package com.soma.wes.collab.service

import com.soma.wes.collab.dto.response.CollabPhotoPageResponse
import com.soma.wes.collab.dto.response.CollabSessionResponse
import com.soma.wes.collab.dto.response.CollabViewerCommentResponse
import com.soma.wes.collab.repository.CollabPhotoCommentRepository
import com.soma.wes.collab.repository.CollabSessionRepository
import com.soma.wes.collab.repository.requireByIdAndGalleryId
import com.soma.wes.collab.support.CollabLinkResolver
import com.soma.wes.collab.support.CollabPhotoViewAssembler
import com.soma.wes.gallery.support.GalleryAccessPolicy
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CollabSessionQueryService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val sessionRepository: CollabSessionRepository,
    private val commentRepository: CollabPhotoCommentRepository,
    private val photoViewAssembler: CollabPhotoViewAssembler,
    private val urlResolver: CollabLinkResolver,
) {
    @Transactional(readOnly = true)
    fun list(galleryId: Long, userId: Long): List<CollabSessionResponse> {
        galleryAccessPolicy.requireViewer(galleryId, userId)
        return sessionRepository.findAllByGalleryIdOrderByCreatedAtDesc(galleryId).map(::toResponse)
    }

    @Transactional(readOnly = true)
    fun get(galleryId: Long, sessionId: Long, userId: Long): CollabSessionResponse {
        galleryAccessPolicy.requireViewer(galleryId, userId)
        return toResponse(sessionRepository.requireByIdAndGalleryId(sessionId, galleryId))
    }

    @Transactional(readOnly = true)
    fun listPhotos(galleryId: Long, sessionId: Long, userId: Long, page: Int, size: Int): CollabPhotoPageResponse {
        galleryAccessPolicy.requireViewer(galleryId, userId)
        val session = sessionRepository.requireByIdAndGalleryId(sessionId, galleryId)
        return photoViewAssembler.toPage(session.requiredId, session.conceptFolderId, page, size)
    }

    @Transactional(readOnly = true)
    fun listViewerComments(galleryId: Long, userId: Long, limit: Int = 200): List<CollabViewerCommentResponse> {
        galleryAccessPolicy.requireViewer(galleryId, userId)
        val sessionIds = sessionRepository.findAllByGalleryIdOrderByCreatedAtDesc(galleryId).map { it.requiredId }
        if (sessionIds.isEmpty()) return emptyList()
        return commentRepository.findAllByCollabSessionIdInOrderByIdDesc(
            sessionIds,
            PageRequest.of(0, limit.coerceIn(1, 200)),
        ).content.map { comment ->
            CollabViewerCommentResponse(
                commentId = comment.requiredId,
                sessionId = comment.collabSessionId,
                photoId = comment.photoId,
                content = comment.content,
                createdAt = comment.createdAt,
            )
        }
    }

    private fun toResponse(session: com.soma.wes.collab.domain.CollabSession) = CollabSessionResponse.of(
        session,
        urlResolver.resolve(session.collabToken),
        photoViewAssembler.count(session.conceptFolderId),
    )
}
