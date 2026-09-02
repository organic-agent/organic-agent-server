package com.soma.wes.collab.controller

import com.soma.wes.collab.dto.request.EnterCollabRequest
import com.soma.wes.collab.dto.request.WriteCollabCommentRequest
import com.soma.wes.collab.controller.docs.CollabGuestControllerDocs
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

@RestController
@RequestMapping("/api/v1/collab/{collabToken}")
class CollabGuestController(
    private val queryService: CollabGuestQueryService,
    private val service: CollabGuestService,
) : CollabGuestControllerDocs {
    @GetMapping
    override fun getLanding(@PathVariable collabToken: String): ResponseEntity<CollabLandingResponse> =
        ResponseEntity.ok(queryService.getLanding(collabToken))

    @PostMapping("/guests")
    override fun enter(
        @PathVariable collabToken: String,
        @RequestBody request: EnterCollabRequest,
    ): ResponseEntity<CollabGuestResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(service.enter(collabToken, request))

    @GetMapping("/photos")
    override fun listPhotos(
        @PathVariable collabToken: String,
        @RequestHeader(name = GuestTokenHeader.NAME, required = false) guestToken: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): ResponseEntity<CollabPhotoPageResponse> =
        ResponseEntity.ok(queryService.listPhotos(collabToken, guestToken, page, size))

    @GetMapping("/photos/{photoId}/comments")
    override fun listComments(
        @PathVariable collabToken: String,
        @PathVariable photoId: Long,
        @RequestHeader(name = GuestTokenHeader.NAME, required = false) guestToken: String?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): ResponseEntity<PageResponse<CollabCommentResponse>> =
        ResponseEntity.ok(queryService.listComments(collabToken, photoId, guestToken, page, size))

    @PostMapping("/photos/{photoId}/comments")
    override fun writeComment(
        @PathVariable collabToken: String,
        @PathVariable photoId: Long,
        @RequestHeader(name = GuestTokenHeader.NAME, required = false) guestToken: String?,
        @RequestBody request: WriteCollabCommentRequest,
    ): ResponseEntity<CollabCommentResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(service.writeComment(collabToken, photoId, guestToken, request))

    @DeleteMapping("/comments/{commentId}")
    override fun deleteComment(
        @PathVariable collabToken: String,
        @PathVariable commentId: Long,
        @RequestHeader(name = GuestTokenHeader.NAME, required = false) guestToken: String?,
    ): ResponseEntity<Unit> {
        service.deleteComment(collabToken, commentId, guestToken)
        return ResponseEntity.noContent().build()
    }

    @PutMapping("/photos/{photoId}/like")
    override fun like(
        @PathVariable collabToken: String,
        @PathVariable photoId: Long,
        @RequestHeader(name = GuestTokenHeader.NAME, required = false) guestToken: String?,
    ): ResponseEntity<Unit> {
        service.like(collabToken, photoId, guestToken)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/photos/{photoId}/like")
    override fun cancelLike(
        @PathVariable collabToken: String,
        @PathVariable photoId: Long,
        @RequestHeader(name = GuestTokenHeader.NAME, required = false) guestToken: String?,
    ): ResponseEntity<Unit> {
        service.cancelLike(collabToken, photoId, guestToken)
        return ResponseEntity.noContent().build()
    }
}
