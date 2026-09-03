package com.soma.wes.collab.service

import com.soma.wes.collab.domain.CollabGuest
import com.soma.wes.collab.domain.CollabPhotoComment
import com.soma.wes.collab.domain.CollabPhotoLike
import com.soma.wes.collab.dto.request.EnterCollabRequest
import com.soma.wes.collab.dto.request.WriteCollabCommentRequest
import com.soma.wes.collab.dto.response.CollabCommentResponse
import com.soma.wes.collab.dto.response.CollabGuestResponse
import com.soma.wes.collab.exception.CollabErrorCode
import com.soma.wes.collab.exception.CollabException
import com.soma.wes.collab.repository.CollabGuestRepository
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
    private val guestRepository: CollabGuestRepository,
    private val commentRepository: CollabPhotoCommentRepository,
    private val likeRepository: CollabPhotoLikeRepository,
    private val photoViewAssembler: CollabPhotoViewAssembler,
    private val productChildTrashService: ProductChildTrashService,
    private val tokenGenerator: SecureTokenGenerator,
) {
    @Transactional
    fun enter(collabToken: String, request: EnterCollabRequest): CollabGuestResponse {
        val access = sessionAccess.requireWritable(collabToken)
        return CollabGuestResponse.from(
            guestRepository.save(
                CollabGuest(access.sessionId, tokenGenerator.generate(), CollabGuest.requireValidNickname(request.nickname)),
            ),
        )
    }

    @Transactional
    fun writeComment(
        collabToken: String,
        photoId: Long,
        guestToken: String?,
        request: WriteCollabCommentRequest,
    ): CollabCommentResponse {
        val access = sessionAccess.requireWritable(collabToken)
        val guest = sessionAccess.requireGuest(access, guestToken)
        requireSharedPhoto(access.session.conceptFolderId, photoId)
        val comment = commentRepository.save(
            CollabPhotoComment(
                collabSessionId = access.sessionId,
                photoId = photoId,
                collabGuestId = guest.requiredId,
                content = CollabPhotoComment.requireValidContent(request.content),
            ),
        )
        return CollabCommentResponse(comment.requiredId, guest.nickname, comment.content, comment.createdAt, true)
    }

    @Transactional
    fun deleteComment(collabToken: String, commentId: Long, guestToken: String?) {
        val access = sessionAccess.requireWritable(collabToken)
        val guest = sessionAccess.requireGuest(access, guestToken)
        val comment = commentRepository.findById(commentId)
            .orElseThrow { CollabException(CollabErrorCode.COMMENT_NOT_FOUND) }
        if (comment.collabSessionId != access.sessionId || !comment.isWrittenBy(guest.requiredId)) {
            throw CollabException(CollabErrorCode.COMMENT_NOT_OWNED)
        }
        if (!productChildTrashService.deleteGuestComment(access.sessionId, commentId, guest.requiredId)) {
            throw CollabException(CollabErrorCode.COMMENT_NOT_FOUND)
        }
    }

    @Transactional
    fun like(collabToken: String, photoId: Long, guestToken: String?) {
        val access = sessionAccess.requireWritable(collabToken)
        val guest = sessionAccess.requireGuest(access, guestToken)
        requireSharedPhoto(access.session.conceptFolderId, photoId)
        if (likeRepository.existsByCollabSessionIdAndPhotoIdAndCollabGuestId(
                access.sessionId,
                photoId,
                guest.requiredId,
            )
        ) return
        likeRepository.save(CollabPhotoLike(access.sessionId, photoId, guest.requiredId))
    }

    @Transactional
    fun cancelLike(collabToken: String, photoId: Long, guestToken: String?) {
        val access = sessionAccess.requireWritable(collabToken)
        val guest = sessionAccess.requireGuest(access, guestToken)
        requireSharedPhoto(access.session.conceptFolderId, photoId)
        productChildTrashService.cancelGuestLike(access.sessionId, photoId, guest.requiredId)
    }

    private fun requireSharedPhoto(conceptFolderId: Long, photoId: Long) {
        if (!photoViewAssembler.contains(conceptFolderId, photoId)) {
            throw CollabException(CollabErrorCode.COLLAB_PHOTO_NOT_FOUND)
        }
    }
}
