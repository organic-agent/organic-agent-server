package com.soma.wes.collab.service

import com.soma.wes.collab.dto.response.CollabCommentResponse
import com.soma.wes.collab.dto.response.CollabParticipantResponse
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
import com.soma.wes.collab.support.CollabPhotoMembership
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
    private val photoRepository: PhotoRepository,
    private val photoViewAssembler: CollabPhotoViewAssembler,
    private val urlResolver: CollabLinkResolver,
    private val membership: CollabPhotoMembership,
) {
    @Transactional(readOnly = true)
    fun list(galleryId: Long, userId: Long): List<CollabSessionResponse> {
        galleryAccessPolicy.requireParticipantViewer(galleryId, userId)
        val sessions = sessionRepository.findAllByGalleryIdOrderByCreatedAtDesc(galleryId)
        val ids = membership.photoIds(sessions)
        val participants = if (sessions.isEmpty()) emptyMap() else {
            participantRepository.countBySessionIds(sessions.map { it.requiredId }).associate { it.sessionId to it.count }
        }
        return sessions.map { session -> CollabSessionResponse.of(
            session,
            urlResolver.resolve(session.collabToken),
            ids[session.requiredId].orEmpty().size.toLong(),
            participants[session.requiredId] ?: 0,
        ) }
    }

    @Transactional(readOnly = true)
    fun get(galleryId: Long, sessionId: Long, userId: Long): CollabSessionResponse {
        galleryAccessPolicy.requireParticipantViewer(galleryId, userId)
        return toResponse(sessionRepository.requireByIdAndGalleryId(sessionId, galleryId))
    }

    /** 폐기·만료된 링크로 들어왔던 사람도 그대로 보여 준다. 누가 다녀갔는지는 링크를 거둔 뒤에도 궁금하다. */
    @Transactional(readOnly = true)
    fun listParticipants(galleryId: Long, sessionId: Long, userId: Long): List<CollabParticipantResponse> {
        galleryAccessPolicy.requireParticipantViewer(galleryId, userId)
        val session = sessionRepository.requireByIdAndGalleryId(sessionId, galleryId)
        return participantRepository.findAllByCollabSessionIdOrderByIdAsc(session.requiredId)
            .map(CollabParticipantResponse::from)
    }

    @Transactional(readOnly = true)
    fun listPhotos(galleryId: Long, sessionId: Long, userId: Long, page: Int, size: Int): CollabPhotoPageResponse {
        galleryAccessPolicy.requireParticipantViewer(galleryId, userId)
        val session = sessionRepository.requireByIdAndGalleryId(sessionId, galleryId)
        return photoViewAssembler.toPage(session, page, size)
    }

    /** 폐기된 링크의 의견도 갤러리 구성원은 읽되, 현재 공유폴더에서 빠진 사진의 의견은 노출하지 않는다. */
    @Transactional(readOnly = true)
    fun listPhotoComments(
        galleryId: Long,
        sessionId: Long,
        photoId: Long,
        userId: Long,
        page: Int,
        size: Int,
    ): PageResponse<CollabCommentResponse> {
        galleryAccessPolicy.requireParticipantViewer(galleryId, userId)

        val session = sessionRepository.requireByIdAndGalleryId(sessionId, galleryId)
        photoRepository.findByIdAndGalleryId(photoId, galleryId)
            ?: throw CollabException(CollabErrorCode.COLLAB_PHOTO_NOT_FOUND)
        if (!photoViewAssembler.contains(session, photoId)) {
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
        galleryAccessPolicy.requireParticipantViewer(galleryId, userId)
        val sessions = sessionRepository.findAllByGalleryIdOrderByCreatedAtDesc(galleryId)
        if (sessions.isEmpty()) return emptyList()
        val sessionIds = sessions.map { it.requiredId }
        val shared = membership.photoIds(sessions).mapValues { it.value.toSet() }
        return commentRepository.findAllByCollabSessionIdInOrderByIdDesc(
            sessionIds,
            PageRequest.of(0, limit.coerceIn(1, 200)),
        ).content.filter { it.photoId in shared[it.collabSessionId].orEmpty() }.map { comment ->
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
        photoViewAssembler.count(session),
        participantRepository.countByCollabSessionId(session.requiredId),
    )

    companion object {
        /** 삭제된 참여자의 댓글에 표시하는 익명 이름. */
        private const val UNKNOWN_NICKNAME = "알 수 없음"
    }
}
