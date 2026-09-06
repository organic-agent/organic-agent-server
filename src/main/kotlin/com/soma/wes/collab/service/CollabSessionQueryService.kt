package com.soma.wes.collab.service

import com.soma.wes.category.repository.ConceptFolderRepository
import com.soma.wes.collab.dto.response.CollabCommentResponse
import com.soma.wes.collab.dto.response.CollabPhotoPageResponse
import com.soma.wes.collab.dto.response.CollabSessionResponse
import com.soma.wes.collab.dto.response.CollabViewerCommentResponse
import com.soma.wes.collab.exception.CollabErrorCode
import com.soma.wes.collab.exception.CollabException
import com.soma.wes.collab.repository.CollabParticipantRepository
import com.soma.wes.collab.repository.CollabPhotoCommentRepository
import com.soma.wes.collab.repository.CollabSessionRepository
import com.soma.wes.collab.repository.requireByIdAndGalleryId
import com.soma.wes.collab.support.CollabLinkResolver
import com.soma.wes.collab.support.CollabPhotoViewAssembler
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.global.page.PageRequests
import com.soma.wes.global.page.PageResponse
import com.soma.wes.photo.repository.PhotoRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CollabSessionQueryService(
    private val galleryAccessPolicy: GalleryAccessPolicy,
    private val sessionRepository: CollabSessionRepository,
    private val commentRepository: CollabPhotoCommentRepository,
    private val participantRepository: CollabParticipantRepository,
    private val conceptRepository: ConceptFolderRepository,
    private val photoRepository: PhotoRepository,
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

    /** 폐기된 링크의 의견도 갤러리 구성원은 읽되, 현재 컨셉에서 빠진 사진의 의견은 노출하지 않는다. */
    @Transactional(readOnly = true)
    fun listPhotoComments(
        galleryId: Long,
        sessionId: Long,
        photoId: Long,
        userId: Long,
        page: Int,
        size: Int,
    ): PageResponse<CollabCommentResponse> {
        galleryAccessPolicy.requireViewer(galleryId, userId)

        val session = sessionRepository.requireByIdAndGalleryId(sessionId, galleryId)
        conceptRepository.findByIdAndGalleryId(session.conceptFolderId, galleryId)
            ?: throw CollabException(CollabErrorCode.SESSION_NOT_FOUND)
        photoRepository.findByIdAndGalleryId(photoId, galleryId)
            ?: throw CollabException(CollabErrorCode.COLLAB_PHOTO_NOT_FOUND)
        if (!photoViewAssembler.contains(session.conceptFolderId, photoId)) {
            throw CollabException(CollabErrorCode.COLLAB_PHOTO_NOT_FOUND)
        }

        val found = commentRepository.findAllByCollabSessionIdAndPhotoIdOrderByIdDesc(
            sessionId,
            photoId,
            PageRequests.of(page, size),
        )
        val participants = participantRepository.findAllByIdIn(found.content.map { it.participantId })
            .associateBy { it.requiredId }

        return PageResponse.of(
            found = found,
            contents = found.content.map { comment ->
                val participant = participants[comment.participantId]
                CollabCommentResponse(
                    commentId = comment.requiredId,
                    nickname = participant?.nickname ?: UNKNOWN_NICKNAME,
                    content = comment.content,
                    createdAt = comment.createdAt,
                    mine = participant?.userId == userId,
                )
            },
        )
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

    companion object {
        /** 삭제된 참여자의 댓글에 표시하는 익명 이름. */
        private const val UNKNOWN_NICKNAME = "알 수 없음"
    }
}
