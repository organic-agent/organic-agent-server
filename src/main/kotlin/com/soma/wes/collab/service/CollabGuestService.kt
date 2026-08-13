package com.soma.wes.collab.service

import com.soma.wes.collab.domain.CollabGuest
import com.soma.wes.collab.domain.CollabPhotoComment
import com.soma.wes.collab.domain.CollabPhotoVote
import com.soma.wes.collab.dto.request.EnterCollabRequest
import com.soma.wes.collab.dto.request.VoteCollabPhotoRequest
import com.soma.wes.collab.dto.request.WriteCollabCommentRequest
import com.soma.wes.collab.dto.response.CollabCommentResponse
import com.soma.wes.collab.dto.response.CollabGuestResponse
import com.soma.wes.collab.exception.CollabErrorCode
import com.soma.wes.collab.exception.CollabException
import com.soma.wes.collab.repository.CollabGuestRepository
import com.soma.wes.collab.repository.CollabPhotoCommentRepository
import com.soma.wes.collab.repository.CollabPhotoRepository
import com.soma.wes.collab.repository.CollabPhotoVoteRepository
import com.soma.wes.collab.repository.requireByIdAndCollabSessionId
import com.soma.wes.collab.repository.requireWithLockByIdAndCollabSessionId
import com.soma.wes.collab.support.CollabSessionAccess
import com.soma.wes.global.SecureTokenGenerator
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 하객이 링크로 **남기는** 것들 — 입장, 댓글, 반응. 보는 쪽은 [CollabGuestQueryService]다.
 *
 * 갈라 둔 것은 문이 다르기 때문이다. 보는 것은 하객 토큰 없이도 되고 마감된 뒤에도 열려
 * 있지만, 남기는 것은 누가 남겼는지 반드시 있어야 하고([CollabSessionAccess.requireGuest])
 * 부부가 고르는 동안에만 열린다([CollabSessionAccess.requireWritable]). 한 클래스에 섞으면
 * 새 메서드를 더할 때 어느 쪽 규칙을 따르는지가 메서드마다 달라진다.
 *
 * 입장이 조회가 아니라 여기 있는 이유도 같다 — 하객 행을 만드는 쓰기이고, 지나는 문도
 * 댓글·반응과 같다.
 */
@Service
class CollabGuestService(
    private val collabSessionAccess: CollabSessionAccess,
    private val collabPhotoRepository: CollabPhotoRepository,
    private val collabGuestRepository: CollabGuestRepository,
    private val collabPhotoCommentRepository: CollabPhotoCommentRepository,
    private val collabPhotoVoteRepository: CollabPhotoVoteRepository,
    private val tokenGenerator: SecureTokenGenerator,
) {

    /**
     * 닉네임을 적고 들어온다. 서버가 하객 토큰을 발급한다.
     *
     * 이미 들어온 사람이 다시 부르면 새 하객이 하나 더 생긴다 — 토큰을 들고 있다면 화면이 이
     * API를 부를 이유가 없고, 부른다는 것은 토큰을 잃었거나 다른 기기라는 뜻이라 새 사람으로
     * 보는 편이 맞다. 같은 닉네임을 막지 않는 이유도 같다.
     *
     * 의견을 받지 않는 상태면 입장 자체가 막힌다([CollabSessionAccess.requireWritable]).
     * 들여보내 봐야 할 수 있는 일이 없는데, 닉네임만 받아두면 하객은 그것을 댓글을 쓸 수 있다는
     * 뜻으로 읽는다.
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
     *
     * 남의 댓글은 지우지 못한다. 부적절한 말을 치우는 것은 부부와 작가의 몫이고, 그쪽은 로그인한
     * 경로([CollabSessionService.deleteComment])로 지운다 — 하객 토큰은 브라우저에 저장된 값이라
     * 그것 하나로 남의 글을 지우게 두면 링크를 가진 누구나 댓글창을 비울 수 있다.
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
        collabPhotoCommentRepository.delete(comment)
    }

    /**
     * 사진에 반응을 남기거나 바꾼다. 하객 한 사람의 표는 하나라 두 번째부터는 덮어쓴다.
     *
     * 사진 행을 잠그고 시작한다([requireWithLockByIdAndCollabSessionId]) — 잠그지 않으면 연타한
     * 요청 둘이 나란히 "아직 없다"를 읽는다.
     */
    @Transactional
    fun vote(
        collabToken: String,
        collabPhotoId: Long,
        guestToken: String?,
        request: VoteCollabPhotoRequest,
    ) {
        val access = collabSessionAccess.requireWritable(collabToken)
        val guest = collabSessionAccess.requireGuest(access, guestToken)
        collabPhotoRepository.requireWithLockByIdAndCollabSessionId(collabPhotoId, access.sessionId)

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
    fun cancelVote(collabToken: String, collabPhotoId: Long, guestToken: String?) {
        val access = collabSessionAccess.requireWritable(collabToken)
        val guest = collabSessionAccess.requireGuest(access, guestToken)
        collabPhotoRepository.requireByIdAndCollabSessionId(collabPhotoId, access.sessionId)

        collabPhotoVoteRepository.deleteByCollabPhotoIdAndCollabGuestId(collabPhotoId, guest.requiredId)
    }
}
