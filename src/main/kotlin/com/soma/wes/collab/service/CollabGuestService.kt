package com.soma.wes.collab.service

import com.soma.wes.collab.domain.CollabParticipant
import com.soma.wes.collab.domain.CollabPhotoComment
import com.soma.wes.collab.domain.CollabPhotoLike
import com.soma.wes.collab.dto.request.EnterCollabRequest
import com.soma.wes.collab.dto.request.WriteCollabCommentRequest
import com.soma.wes.collab.dto.response.CollabCommentResponse
import com.soma.wes.collab.dto.response.CollabGuestResponse
import com.soma.wes.collab.exception.CollabErrorCode
import com.soma.wes.collab.exception.CollabException
import com.soma.wes.collab.repository.CollabParticipantRepository
import com.soma.wes.collab.repository.CollabPhotoCommentRepository
import com.soma.wes.collab.repository.CollabPhotoLikeRepository
import com.soma.wes.collab.support.CollabPhotoViewAssembler
import com.soma.wes.collab.support.CollabSessionAccess
import com.soma.wes.global.SecureTokenGenerator
import com.soma.wes.trash.service.ProductChildTrashService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class CollabGuestService(
    private val sessionAccess: CollabSessionAccess,
    private val participantRepository: CollabParticipantRepository,
    private val commentRepository: CollabPhotoCommentRepository,
    private val likeRepository: CollabPhotoLikeRepository,
    private val photoViewAssembler: CollabPhotoViewAssembler,
    private val productChildTrashService: ProductChildTrashService,
    private val tokenGenerator: SecureTokenGenerator,
) {
    @Transactional
    fun enter(collabToken: String, request: EnterCollabRequest): CollabGuestResponse {
        val access = sessionAccess.requireReadable(collabToken)
        return CollabGuestResponse.from(
            participantRepository.save(
                CollabParticipant.guest(access.sessionId, tokenGenerator.generate(), request.nickname),
            ),
        )
    }

    @Transactional
    fun renameGuest(collabToken: String, guestToken: String?, request: EnterCollabRequest): CollabGuestResponse {
        val access = sessionAccess.requireReadable(collabToken)
        val guest = sessionAccess.requireGuest(access, guestToken)
        guest.nickname = CollabParticipant.requireValidNickname(request.nickname)
        return CollabGuestResponse.from(guest)
    }

    @Transactional
    fun writeComment(
        collabToken: String,
        photoId: Long,
        userId: Long?,
        guestToken: String?,
        request: WriteCollabCommentRequest,
    ): CollabCommentResponse {
        val access = sessionAccess.requireWritable(collabToken)
        val participant = sessionAccess.requireParticipant(access, userId, guestToken)
        requireSharedPhoto(access.session.conceptFolderId, photoId)
        val comment = commentRepository.save(
            CollabPhotoComment(
                collabSessionId = access.sessionId,
                photoId = photoId,
                participantId = participant.requiredId,
                content = CollabPhotoComment.requireValidContent(request.content),
            ),
        )
        return CollabCommentResponse(comment.requiredId, participant.nickname, comment.content, comment.createdAt, true)
    }

    fun writeComment(
        collabToken: String,
        photoId: Long,
        guestToken: String?,
        request: WriteCollabCommentRequest,
    ): CollabCommentResponse = writeComment(collabToken, photoId, null, guestToken, request)

    @Transactional
    fun deleteComment(collabToken: String, commentId: Long, userId: Long?, guestToken: String?) {
        val access = sessionAccess.requireWritable(collabToken)
        val participant = sessionAccess.requireParticipant(access, userId, guestToken)
        val comment = commentRepository.findById(commentId)
            .orElseThrow { CollabException(CollabErrorCode.COMMENT_NOT_FOUND) }
        if (comment.collabSessionId != access.sessionId || !comment.isWrittenBy(participant.requiredId)) {
            throw CollabException(CollabErrorCode.COMMENT_NOT_OWNED)
        }
        if (!productChildTrashService.deleteParticipantComment(access.sessionId, commentId, participant.requiredId)) {
            throw CollabException(CollabErrorCode.COMMENT_NOT_FOUND)
        }
    }

    fun deleteComment(collabToken: String, commentId: Long, guestToken: String?) =
        deleteComment(collabToken, commentId, null, guestToken)

    @Transactional
    fun like(collabToken: String, photoId: Long, userId: Long?, guestToken: String?) {
        val access = sessionAccess.requireWritable(collabToken)
        val participant = sessionAccess.requireParticipant(access, userId, guestToken)
        requireSharedPhoto(access.session.conceptFolderId, photoId)
        if (likeRepository.existsByCollabSessionIdAndPhotoIdAndParticipantId(
                access.sessionId,
                photoId,
                participant.requiredId,
            )
        ) return
        likeRepository.save(CollabPhotoLike(access.sessionId, photoId, participant.requiredId))
    }

    fun like(collabToken: String, photoId: Long, guestToken: String?) =
        like(collabToken, photoId, null, guestToken)

    @Transactional
    fun cancelLike(collabToken: String, photoId: Long, userId: Long?, guestToken: String?) {
        val access = sessionAccess.requireWritable(collabToken)
        val participant = sessionAccess.requireParticipant(access, userId, guestToken)
        requireSharedPhoto(access.session.conceptFolderId, photoId)
        productChildTrashService.cancelParticipantLike(access.sessionId, photoId, participant.requiredId)
    }

    fun cancelLike(collabToken: String, photoId: Long, guestToken: String?) =
        cancelLike(collabToken, photoId, null, guestToken)

    private fun requireSharedPhoto(conceptFolderId: Long, photoId: Long) {
        if (!photoViewAssembler.contains(conceptFolderId, photoId)) {
            throw CollabException(CollabErrorCode.COLLAB_PHOTO_NOT_FOUND)
        }
    }
}
