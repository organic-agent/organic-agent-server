package com.soma.wes.collab.controller

import com.soma.wes.collab.controller.docs.CollabFeedbackControllerDocs
import com.soma.wes.collab.dto.request.VoteCollabPhotoRequest
import com.soma.wes.collab.dto.request.WriteCollabCommentRequest
import com.soma.wes.collab.dto.response.CollabCommentResponse
import com.soma.wes.collab.service.CollabFeedbackService
import com.soma.wes.collab.support.GuestTokenHeader
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


/**
 * 하객이 **남기는** 경로. 로그인하지 않고 부르지만 하객 토큰은 반드시 있어야 한다.
 *
 * [CollabShareController]와 갈라 둔 것은 규칙이 다르기 때문이다. 저쪽은 토큰이 없어도 되고
 * 마감된 뒤에도 열려 있지만, 이쪽은 토큰이 필수이고 부부가 고르는 동안에만 열린다.
 */
@RestController
@RequestMapping("/api/v1/collab/{shareToken}")
class CollabFeedbackController(
    private val collabFeedbackService: CollabFeedbackService,
) : CollabFeedbackControllerDocs {

    @PostMapping("/photos/{collabPhotoId}/comments")
    override fun writeComment(
        @PathVariable shareToken: String,
        @PathVariable collabPhotoId: Long,
        @RequestHeader(name = GuestTokenHeader.GUEST_TOKEN, required = false) guestToken: String?,
        @RequestBody request: WriteCollabCommentRequest,
    ): ResponseEntity<CollabCommentResponse> {
        val result = collabFeedbackService.writeComment(shareToken, collabPhotoId, guestToken, request)
        val status = HttpStatus.CREATED

        return ResponseEntity.status(status).body(result)
    }

    @DeleteMapping("/comments/{commentId}")
    override fun deleteComment(
        @PathVariable shareToken: String,
        @PathVariable commentId: Long,
        @RequestHeader(name = GuestTokenHeader.GUEST_TOKEN, required = false) guestToken: String?,
    ): ResponseEntity<Unit> {
        collabFeedbackService.deleteComment(shareToken, commentId, guestToken)
        val status = HttpStatus.NO_CONTENT

        return ResponseEntity.status(status).build()
    }

    @PutMapping("/photos/{collabPhotoId}/vote")
    override fun vote(
        @PathVariable shareToken: String,
        @PathVariable collabPhotoId: Long,
        @RequestHeader(name = GuestTokenHeader.GUEST_TOKEN, required = false) guestToken: String?,
        @RequestBody request: VoteCollabPhotoRequest,
    ): ResponseEntity<Unit> {
        collabFeedbackService.vote(shareToken, collabPhotoId, guestToken, request)
        val status = HttpStatus.NO_CONTENT

        return ResponseEntity.status(status).build()
    }

    @DeleteMapping("/photos/{collabPhotoId}/vote")
    override fun cancelVote(
        @PathVariable shareToken: String,
        @PathVariable collabPhotoId: Long,
        @RequestHeader(name = GuestTokenHeader.GUEST_TOKEN, required = false) guestToken: String?,
    ): ResponseEntity<Unit> {
        collabFeedbackService.cancelVote(shareToken, collabPhotoId, guestToken)
        val status = HttpStatus.NO_CONTENT

        return ResponseEntity.status(status).build()
    }
}
