package com.soma.wes.collab.service

import com.soma.wes.collab.domain.CollabPhoto
import com.soma.wes.collab.domain.CollabPhotoComment
import com.soma.wes.collab.domain.CollabPhotoVote
import com.soma.wes.collab.dto.request.VoteCollabPhotoRequest
import com.soma.wes.collab.dto.request.WriteCollabCommentRequest
import com.soma.wes.collab.dto.response.CollabCommentResponse
import com.soma.wes.collab.exception.CollabErrorCode
import com.soma.wes.collab.exception.CollabException
import com.soma.wes.collab.repository.CollabPhotoCommentRepository
import com.soma.wes.collab.repository.CollabPhotoRepository
import com.soma.wes.collab.repository.CollabPhotoVoteRepository
import com.soma.wes.collab.support.CollabAccess
import com.soma.wes.collab.support.CollabSessionAccess
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 하객이 남기는 것들 — 댓글과 반응.
 *
 * 조회([CollabGuestService])와 갈라 둔 것은 문이 다르기 때문이다. 보는 것은 하객 토큰 없이도
 * 되지만 남기는 것은 누가 남겼는지 반드시 있어야 하고, 마감된 갤러리에서는 보는 것만 된다.
 * 한 서비스에 섞으면 새 메서드를 더할 때 어느 쪽 규칙을 따르는지가 메서드마다 달라진다.
 */
@Service
class CollabFeedbackService(
    private val collabSessionAccess: CollabSessionAccess,
    private val collabPhotoRepository: CollabPhotoRepository,
    private val collabPhotoCommentRepository: CollabPhotoCommentRepository,
    private val collabPhotoVoteRepository: CollabPhotoVoteRepository,
) {

    /** 사진 한 장에 댓글을 남긴다. 수정은 없다 — 지우고 다시 쓴다. */
    @Transactional
    fun writeComment(
        shareToken: String,
        collabPhotoId: Long,
        guestToken: String?,
        request: WriteCollabCommentRequest,
    ): CollabCommentResponse {
        val access = collabSessionAccess.requireWritable(shareToken)
        val guest = collabSessionAccess.requireGuest(access, guestToken)
        requireCollabPhoto(access, collabPhotoId)

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
     *
     * 남의 댓글은 지우지 못한다. 부적절한 말을 치우는 것은 부부와 작가의 몫이고, 그쪽은 로그인한
     * 경로([CollabSessionService.deleteComment])로 지운다 — 하객 토큰은 브라우저에 저장된 값이라
     * 그것 하나로 남의 글을 지우게 두면 링크를 가진 누구나 댓글창을 비울 수 있다.
     */
    @Transactional
    fun deleteComment(shareToken: String, commentId: Long, guestToken: String?) {
        val access = collabSessionAccess.requireWritable(shareToken)
        val guest = collabSessionAccess.requireGuest(access, guestToken)

        val comment = collabPhotoCommentRepository.findById(commentId)
            .orElseThrow { CollabException(CollabErrorCode.COMMENT_NOT_FOUND) }
        // 다른 세션의 댓글 id를 넣어보는 요청을 걸러낸다. 없는 것과 같게 취급해야
        // 남의 세션에 그 id의 댓글이 있다는 사실조차 알려주지 않는다.
        requireCollabPhoto(access, comment.collabPhotoId)

        if (!comment.isWrittenBy(guest.requiredId)) {
            throw CollabException(CollabErrorCode.COMMENT_NOT_OWNED)
        }
        collabPhotoCommentRepository.delete(comment)
    }

    /**
     * 사진에 반응을 남기거나 바꾼다. 하객 한 사람의 표는 하나라 두 번째부터는 덮어쓴다.
     *
     * 사진 행을 잠그고 시작한다([CollabPhotoRepository.findWithLockByIdAndCollabSessionId]).
     * 버튼을 연타하면 같은 하객의 요청 둘이 겹치는데, 잠그지 않으면 둘 다 "아직 없다"를 읽고
     * 각자 INSERT 해 한쪽이 유니크 제약에서 500으로 끝난다.
     */
    @Transactional
    fun vote(
        shareToken: String,
        collabPhotoId: Long,
        guestToken: String?,
        request: VoteCollabPhotoRequest,
    ) {
        val access = collabSessionAccess.requireWritable(shareToken)
        val guest = collabSessionAccess.requireGuest(access, guestToken)
        lockCollabPhoto(access, collabPhotoId)

        val vote = collabPhotoVoteRepository.findByCollabPhotoIdAndCollabGuestId(collabPhotoId, guest.requiredId)
        if (vote != null) {
            vote.changeReaction(request.reaction)
            return
        }

        collabPhotoVoteRepository.save(
            CollabPhotoVote(
                collabPhotoId = collabPhotoId,
                collabGuestId = guest.requiredId,
                reaction = request.reaction,
            ),
        )
    }

    /**
     * 눌렀던 반응을 거둔다. 누른 적이 없어도 성공한다.
     *
     * "표가 없는 상태"가 목적이고 그것은 이미 이뤄져 있다. 404를 돌려주면 화면은 방금 지운
     * 버튼을 두고 실패했다고 알려야 한다.
     */
    @Transactional
    fun cancelVote(shareToken: String, collabPhotoId: Long, guestToken: String?) {
        val access = collabSessionAccess.requireWritable(shareToken)
        val guest = collabSessionAccess.requireGuest(access, guestToken)
        requireCollabPhoto(access, collabPhotoId)

        collabPhotoVoteRepository.deleteByCollabPhotoIdAndCollabGuestId(collabPhotoId, guest.requiredId)
    }

    /**
     * 이 세션에 담긴 사진인지. 세션을 함께 보지 않으면 남의 세션 사진에 글을 남길 수 있다 —
     * 링크는 갤러리마다 다르지만 협업 사진 id는 전역에서 이어지는 값이다.
     */
    private fun requireCollabPhoto(access: CollabAccess, collabPhotoId: Long): CollabPhoto =
        collabPhotoRepository.findByIdAndCollabSessionId(collabPhotoId, access.sessionId)
            ?: throw CollabException(CollabErrorCode.COLLAB_PHOTO_NOT_FOUND)

    /** [vote]만 쓴다. 확인과 잠금을 한 번에 한다. */
    private fun lockCollabPhoto(access: CollabAccess, collabPhotoId: Long): CollabPhoto =
        collabPhotoRepository.findWithLockByIdAndCollabSessionId(collabPhotoId, access.sessionId)
            ?: throw CollabException(CollabErrorCode.COLLAB_PHOTO_NOT_FOUND)
}
