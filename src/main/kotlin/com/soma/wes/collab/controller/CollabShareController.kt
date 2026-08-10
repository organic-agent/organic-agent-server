package com.soma.wes.collab.controller

import com.soma.wes.collab.controller.docs.CollabShareControllerDocs
import com.soma.wes.collab.dto.request.EnterCollabRequest
import com.soma.wes.collab.dto.response.CollabCommentPageResponse
import com.soma.wes.collab.dto.response.CollabGuestResponse
import com.soma.wes.collab.dto.response.CollabLandingResponse
import com.soma.wes.collab.dto.response.CollabPhotoPageResponse
import com.soma.wes.collab.service.CollabGuestService
import com.soma.wes.collab.support.GuestTokenHeader
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController


/**
 * 하객이 링크로 들어와 **보는** 경로. 로그인하지 않고 부른다
 * ([com.soma.wes.security.PublicPaths]).
 *
 * 하객 토큰은 여기서 선택이다. 있으면 자기가 누른 반응과 자기가 쓴 댓글이 표시되고, 없으면
 * 그 자리만 비어 온다 — 사진을 보기도 전에 닉네임부터 받게 하지 않으려는 것이다.
 * 남기는 경로는 [CollabFeedbackController]이고 그쪽은 토큰이 필수다.
 */
@RestController
@RequestMapping("/api/v1/collab/{shareToken}")
class CollabShareController(
    private val collabGuestService: CollabGuestService,
) : CollabShareControllerDocs {

    @GetMapping
    override fun open(
        @PathVariable shareToken: String,
    ): ResponseEntity<CollabLandingResponse> {
        val result = collabGuestService.open(shareToken)

        return ResponseEntity.ok(result)
    }

    @PostMapping("/guests")
    override fun enter(
        @PathVariable shareToken: String,
        @RequestBody request: EnterCollabRequest,
    ): ResponseEntity<CollabGuestResponse> {
        val result = collabGuestService.enter(shareToken, request)
        val status = HttpStatus.CREATED

        return ResponseEntity.status(status).body(result)
    }

    @GetMapping("/photos")
    override fun listPhotos(
        @PathVariable shareToken: String,
        @RequestHeader(name = GuestTokenHeader.GUEST_TOKEN, required = false) guestToken: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): ResponseEntity<CollabPhotoPageResponse> {
        val result = collabGuestService.listPhotos(shareToken, guestToken, page, size)

        return ResponseEntity.ok(result)
    }

    @GetMapping("/photos/{collabPhotoId}/comments")
    override fun listComments(
        @PathVariable shareToken: String,
        @PathVariable collabPhotoId: Long,
        @RequestHeader(name = GuestTokenHeader.GUEST_TOKEN, required = false) guestToken: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): ResponseEntity<CollabCommentPageResponse> {
        val result = collabGuestService.listComments(shareToken, collabPhotoId, guestToken, page, size)

        return ResponseEntity.ok(result)
    }
}
