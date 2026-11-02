package com.soma.wes.collab.service

import com.soma.wes.collab.dto.request.EnterCollabRequest
import com.soma.wes.collab.dto.request.WriteCollabCommentRequest
import com.soma.wes.collab.exception.CollabErrorCode
import com.soma.wes.collab.exception.CollabException
import com.soma.wes.collab.fixture.CollabFixture
import com.soma.wes.collab.repository.CollabPhotoCommentRepository
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

@IntegrationTest
class CollabGuestServiceTest @Autowired constructor(
    private val service: CollabGuestService,
    private val collabFixture: CollabFixture,
    private val commentRepository: CollabPhotoCommentRepository,
) {
    @Test
    fun `다른 공유폴더의 댓글은 없는 댓글로 거절하고 남겨 둔다`() {
        // given
        val shared = collabFixture.사진이_있는_세션()
        val other = collabFixture.사진이_있는_세션()
        val guest = service.enter(shared.token, EnterCollabRequest("게스트"))
        val writer = service.enter(other.token, EnterCollabRequest("다른 게스트"))
        val foreign = service.writeComment(other.token, other.photoId, null, writer.guestToken, WriteCollabCommentRequest("의견"))

        // when & then
        assertThatThrownBy { service.deleteComment(shared.token, foreign.commentId, null, guest.guestToken) }
            .isInstanceOf(CollabException::class.java)
            .extracting("errorCode").isEqualTo(CollabErrorCode.COMMENT_NOT_FOUND)
        assertThat(commentRepository.existsById(foreign.commentId)).isTrue()
    }

    @Test
    fun `같은 공유폴더의 남이 쓴 댓글은 지울 수 없다`() {
        // given
        val shared = collabFixture.사진이_있는_세션()
        val guest = service.enter(shared.token, EnterCollabRequest("게스트"))
        val writer = service.enter(shared.token, EnterCollabRequest("쓴 사람"))
        val comment = service.writeComment(shared.token, shared.photoId, null, writer.guestToken, WriteCollabCommentRequest("의견"))

        // when & then
        assertThatThrownBy { service.deleteComment(shared.token, comment.commentId, null, guest.guestToken) }
            .isInstanceOf(CollabException::class.java)
            .extracting("errorCode").isEqualTo(CollabErrorCode.COMMENT_NOT_OWNED)
        assertThat(commentRepository.existsById(comment.commentId)).isTrue()
    }
}
