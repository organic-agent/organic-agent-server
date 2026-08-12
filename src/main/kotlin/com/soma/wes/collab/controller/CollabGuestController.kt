package com.soma.wes.collab.controller

import com.soma.wes.collab.controller.docs.CollabGuestControllerDocs
import com.soma.wes.collab.dto.request.EnterCollabRequest
import com.soma.wes.collab.dto.request.VoteCollabPhotoRequest
import com.soma.wes.collab.dto.request.WriteCollabCommentRequest
import com.soma.wes.collab.dto.response.CollabCommentResponse
import com.soma.wes.collab.dto.response.CollabGuestResponse
import com.soma.wes.collab.dto.response.CollabLandingResponse
import com.soma.wes.collab.dto.response.CollabPhotoPageResponse
import com.soma.wes.collab.service.CollabGuestQueryService
import com.soma.wes.collab.service.CollabGuestService
import com.soma.wes.collab.support.GuestTokenHeader
import com.soma.wes.global.page.PageResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController


/**
 * 하객이 링크 하나로 하는 모든 것. 로그인하지 않고 부른다
 * ([com.soma.wes.security.PublicPaths]).
 *
 * 이 URL 공간 전체가 한 클래스다. 보는 것과 남기는 것의 규칙이 다르긴 하지만
 * — 보는 것은 하객 토큰 없이도 되고 마감된 뒤에도 열려 있는 반면, 남기는 것은 토큰이 필수이고
 * 부부가 고르는 동안에만 열린다 — 그 구분은 [com.soma.wes.collab.support.CollabSessionAccess]와
 * 서비스 둘([CollabGuestQueryService]·[CollabGuestService])이 이미 강제한다. 컨트롤러에서 한 번
 * 더 나누면 강제력 없는 세 번째 사본이 되고, 같은 주소의 API를 찾는 사람이 파일 둘을 뒤져야 한다.
 *
 * 로그인해서 같은 세션을 다루는 경로는 [CollabSessionController]다.
 */
@RestController
@RequestMapping("/api/v1/collab/{collabToken}")
class CollabGuestController(
    private val collabGuestQueryService: CollabGuestQueryService,
    private val collabGuestService: CollabGuestService,
) : CollabGuestControllerDocs {

    @GetMapping
    override fun getLanding(
        @PathVariable collabToken: String,
    ): ResponseEntity<CollabLandingResponse> {
        val result = collabGuestQueryService.getLanding(collabToken)

        return ResponseEntity.ok(result)
    }

    @PostMapping("/guests")
    override fun enter(
        @PathVariable collabToken: String,
        @RequestBody request: EnterCollabRequest,
    ): ResponseEntity<CollabGuestResponse> {
        val result = collabGuestService.enter(collabToken, request)
        val status = HttpStatus.CREATED

        return ResponseEntity.status(status).body(result)
    }

    @GetMapping("/photos")
    override fun listPhotos(
        @PathVariable collabToken: String,
        @RequestHeader(name = GuestTokenHeader.NAME, required = false) guestToken: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): ResponseEntity<CollabPhotoPageResponse> {
        val result = collabGuestQueryService.listPhotos(collabToken, guestToken, page, size)

        return ResponseEntity.ok(result)
    }

    @GetMapping("/photos/{collabPhotoId}/comments")
    override fun listComments(
        @PathVariable collabToken: String,
        @PathVariable collabPhotoId: Long,
        @RequestHeader(name = GuestTokenHeader.NAME, required = false) guestToken: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): ResponseEntity<PageResponse<CollabCommentResponse>> {
        val result = collabGuestQueryService.listComments(collabToken, collabPhotoId, guestToken, page, size)

        return ResponseEntity.ok(result)
    }

    @PostMapping("/photos/{collabPhotoId}/comments")
    override fun writeComment(
        @PathVariable collabToken: String,
        @PathVariable collabPhotoId: Long,
        @RequestHeader(name = GuestTokenHeader.NAME, required = false) guestToken: String?,
        @RequestBody request: WriteCollabCommentRequest,
    ): ResponseEntity<CollabCommentResponse> {
        val result = collabGuestService.writeComment(collabToken, collabPhotoId, guestToken, request)
        val status = HttpStatus.CREATED

        return ResponseEntity.status(status).body(result)
    }

    @DeleteMapping("/comments/{commentId}")
    override fun deleteComment(
        @PathVariable collabToken: String,
        @PathVariable commentId: Long,
        @RequestHeader(name = GuestTokenHeader.NAME, required = false) guestToken: String?,
    ): ResponseEntity<Unit> {
        collabGuestService.deleteComment(collabToken, commentId, guestToken)
        val status = HttpStatus.NO_CONTENT

        return ResponseEntity.status(status).build()
    }

    @PutMapping("/photos/{collabPhotoId}/vote")
    override fun vote(
        @PathVariable collabToken: String,
        @PathVariable collabPhotoId: Long,
        @RequestHeader(name = GuestTokenHeader.NAME, required = false) guestToken: String?,
        @RequestBody request: VoteCollabPhotoRequest,
    ): ResponseEntity<Unit> {
        collabGuestService.vote(collabToken, collabPhotoId, guestToken, request)
        val status = HttpStatus.NO_CONTENT

        return ResponseEntity.status(status).build()
    }

    @DeleteMapping("/photos/{collabPhotoId}/vote")
    override fun cancelVote(
        @PathVariable collabToken: String,
        @PathVariable collabPhotoId: Long,
        @RequestHeader(name = GuestTokenHeader.NAME, required = false) guestToken: String?,
    ): ResponseEntity<Unit> {
        collabGuestService.cancelVote(collabToken, collabPhotoId, guestToken)
        val status = HttpStatus.NO_CONTENT

        return ResponseEntity.status(status).build()
    }
}
