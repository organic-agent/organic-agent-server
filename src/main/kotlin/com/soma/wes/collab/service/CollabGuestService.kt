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
import com.soma.wes.collab.repository.CollabPhotoRepository
import com.soma.wes.collab.repository.requireByIdAndCollabSessionId
import com.soma.wes.collab.repository.requireWithLockByIdAndCollabSessionId
import com.soma.wes.collab.support.CollabSessionAccess
import com.soma.wes.global.SecureTokenGenerator
import com.soma.wes.trash.service.ProductChildTrashService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 하객이 링크로 **남기는** 것들 — 입장, 댓글, 좋아요. 보는 쪽은 [CollabGuestQueryService]다.
 */
@Service
class CollabGuestService(
    private val collabSessionAccess: CollabSessionAccess,
    private val collabPhotoRepository: CollabPhotoRepository,
    private val collabGuestRepository: CollabGuestRepository,
    private val collabPhotoCommentRepository: CollabPhotoCommentRepository,
    private val collabPhotoLikeRepository: CollabPhotoLikeRepository,
    private val productChildTrashService: ProductChildTrashService,
    private val tokenGenerator: SecureTokenGenerator,
) {

    /**
     * 닉네임을 적고 들어온다. 서버가 하객 토큰을 발급한다.
     */
    @Transactional
    fun enter(collabToken: String, request: EnterCollabRequest): CollabGuestResponse {
        val access = collabSessionAccess.requireWritable(collabToken)

        val guest = collabGuestRepository.save(
            CollabGuest(
                collabSessionId = access.sessionId,
                guestToken = tokenGenerator.generate(),
                nickname = CollabGuest.requireValidNickname(request.nickname),
            ),
        )
        return CollabGuestResponse.from(guest)
    }

    /** 사진 한 장에 댓글을 남긴다. 수정은 없다 — 지우고 다시 쓴다. */
    @Transactional
    fun writeComment(
        collabToken: String,
        collabPhotoId: Long,
        guestToken: String?,
        request: WriteCollabCommentRequest,
    ): CollabCommentResponse {
        val access = collabSessionAccess.requireWritable(collabToken)
        val guest = collabSessionAccess.requireGuest(access, guestToken)
        collabPhotoRepository.requireByIdAndCollabSessionId(collabPhotoId, access.sessionId)

        val comment = collabPhotoCommentRepository.save(
            CollabPhotoComment(
                collabPhotoId = collabPhotoId,
                collabGuestId = guest.requiredId,
                content = CollabPhotoComment.requireValidContent(request.content),
            ),
        )

        return CollabCommentResponse(
            commentId = comment.requiredId,
            nickname = guest.nickname,
            content = comment.content,
            createdAt = comment.createdAt,
            // 방금 쓴 사람에게 돌려주는 응답이라 언제나 자기 것이다.
            mine = true,
        )
    }

    /**
     * 자기가 쓴 댓글을 지운다.
     */
    @Transactional
    fun deleteComment(collabToken: String, commentId: Long, guestToken: String?) {
        val access = collabSessionAccess.requireWritable(collabToken)
        val guest = collabSessionAccess.requireGuest(access, guestToken)

        val comment = collabPhotoCommentRepository.findById(commentId)
            .orElseThrow { CollabException(CollabErrorCode.COMMENT_NOT_FOUND) }
        // 다른 세션의 댓글 id를 넣어보는 요청을 걸러낸다. 없는 것과 같게 취급해야
        // 남의 세션에 그 id의 댓글이 있다는 사실조차 알려주지 않는다.
        collabPhotoRepository.requireByIdAndCollabSessionId(comment.collabPhotoId, access.sessionId)

        if (!comment.isWrittenBy(guest.requiredId)) {
            throw CollabException(CollabErrorCode.COMMENT_NOT_OWNED)
        }
        if (!productChildTrashService.deleteGuestComment(access.sessionId, commentId, guest.requiredId)) {
            throw CollabException(CollabErrorCode.COMMENT_NOT_FOUND)
        }
    }

    /**
     * 사진에 좋아요를 남긴다. 하객 한 사람의 표는 하나라 이미 눌렀으면 그대로 성공한다.
     */
    @Transactional
    fun like(collabToken: String, collabPhotoId: Long, guestToken: String?) {
        val access = collabSessionAccess.requireWritable(collabToken)
        val guest = collabSessionAccess.requireGuest(access, guestToken)
        collabPhotoRepository.requireWithLockByIdAndCollabSessionId(collabPhotoId, access.sessionId)

        if (collabPhotoLikeRepository.existsByCollabPhotoIdAndCollabGuestId(collabPhotoId, guest.requiredId)) {
            return
        }

        collabPhotoLikeRepository.save(
            CollabPhotoLike(
                collabPhotoId = collabPhotoId,
                collabGuestId = guest.requiredId,
            ),
        )
    }

    /**
     * 눌렀던 좋아요를 거둔다. 누른 적이 없어도 성공한다.
     */
    @Transactional
    fun cancelLike(collabToken: String, collabPhotoId: Long, guestToken: String?) {
        val access = collabSessionAccess.requireWritable(collabToken)
        val guest = collabSessionAccess.requireGuest(access, guestToken)
        collabPhotoRepository.requireByIdAndCollabSessionId(collabPhotoId, access.sessionId)

        productChildTrashService.cancelGuestLike(access.sessionId, collabPhotoId, guest.requiredId)
    }
}
