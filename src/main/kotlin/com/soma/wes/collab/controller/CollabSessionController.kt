package com.soma.wes.collab.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.collab.controller.docs.CollabSessionControllerDocs
import com.soma.wes.collab.dto.request.CollabPhotoIdsRequest
import jakarta.validation.Valid
import com.soma.wes.collab.dto.request.OpenCollabSessionRequest
import com.soma.wes.collab.dto.request.RenameCollabSessionRequest
import com.soma.wes.collab.dto.response.CollabCommentResponse
import com.soma.wes.collab.dto.response.CollabParticipantResponse
import com.soma.wes.collab.dto.response.CollabPhotoPageResponse
import com.soma.wes.collab.dto.response.CollabSessionResponse
import com.soma.wes.collab.service.CollabSessionQueryService
import com.soma.wes.collab.service.CollabSessionService
import com.soma.wes.global.page.PageResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/galleries/{galleryId}/collab-sessions")
class CollabSessionController(
    private val service: CollabSessionService,
    private val queryService: CollabSessionQueryService,
) : CollabSessionControllerDocs {
    @PostMapping
    override fun open(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @Valid @RequestBody request: OpenCollabSessionRequest,
    ): ResponseEntity<CollabSessionResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(service.open(galleryId, loginUser.id, request))

    @GetMapping
    override fun list(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
    ): ResponseEntity<List<CollabSessionResponse>> = ResponseEntity.ok(queryService.list(galleryId, loginUser.id))

    @GetMapping("/{sessionId}")
    override fun get(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable sessionId: Long,
    ): ResponseEntity<CollabSessionResponse> = ResponseEntity.ok(queryService.get(galleryId, sessionId, loginUser.id))

    @PatchMapping("/{sessionId}")
    override fun rename(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable sessionId: Long,
        @RequestBody request: RenameCollabSessionRequest,
    ): ResponseEntity<CollabSessionResponse> =
        ResponseEntity.ok(service.rename(galleryId, sessionId, loginUser.id, request))

    @DeleteMapping("/{sessionId}")
    override fun revoke(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable sessionId: Long,
    ): ResponseEntity<Unit> {
        service.revoke(galleryId, sessionId, loginUser.id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{sessionId}/republish")
    override fun republish(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable sessionId: Long,
    ): ResponseEntity<CollabSessionResponse> =
        ResponseEntity.ok(service.republish(galleryId, sessionId, loginUser.id))

    @PostMapping("/{sessionId}/photos")
    override fun addPhotos(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable sessionId: Long,
        @Valid @RequestBody request: CollabPhotoIdsRequest,
    ): ResponseEntity<CollabSessionResponse> {
        return ResponseEntity.ok(service.addPhotos(galleryId, sessionId, loginUser.id, request))
    }

    @DeleteMapping("/{sessionId}/photos")
    override fun removePhotos(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable sessionId: Long,
        @Valid @RequestBody request: CollabPhotoIdsRequest,
    ): ResponseEntity<CollabSessionResponse> {
        return ResponseEntity.ok(service.removePhotos(galleryId, sessionId, loginUser.id, request))
    }

    @PostMapping("/{sessionId}/convert-to-manual")
    override fun convertToManual(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable sessionId: Long,
    ): ResponseEntity<CollabSessionResponse> {
        return ResponseEntity.ok(service.convertToManual(galleryId, sessionId, loginUser.id))
    }

    @GetMapping("/{sessionId}/participants")
    override fun listParticipants(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable sessionId: Long,
    ): ResponseEntity<List<CollabParticipantResponse>> =
        ResponseEntity.ok(queryService.listParticipants(galleryId, sessionId, loginUser.id))

    @GetMapping("/{sessionId}/photos")
    override fun listPhotos(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable sessionId: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "200") size: Int,
    ): ResponseEntity<CollabPhotoPageResponse> =
        ResponseEntity.ok(queryService.listPhotos(galleryId, sessionId, loginUser.id, page, size))

    @DeleteMapping("/{sessionId}/comments/{commentId}")
    override fun deleteComment(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable sessionId: Long,
        @PathVariable commentId: Long,
    ): ResponseEntity<Unit> {
        service.deleteComment(galleryId, sessionId, commentId, loginUser.id)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/{sessionId}/photos/{photoId}/comments")
    override fun listPhotoComments(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable sessionId: Long,
        @PathVariable photoId: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): ResponseEntity<PageResponse<CollabCommentResponse>> {
        val result = queryService.listPhotoComments(galleryId, sessionId, photoId, loginUser.id, page, size)

        return ResponseEntity.ok(result)
    }
}
