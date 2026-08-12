package com.soma.wes.collab.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.collab.controller.docs.CollabSessionControllerDocs
import com.soma.wes.collab.dto.request.AddCollabPhotosRequest
import com.soma.wes.collab.dto.request.OpenCollabSessionRequest
import com.soma.wes.collab.dto.request.RemoveCollabPhotosRequest
import com.soma.wes.collab.dto.request.RenameCollabSessionRequest
import com.soma.wes.collab.dto.response.CollabPhotoPageResponse
import com.soma.wes.collab.dto.response.CollabSessionResponse
import com.soma.wes.collab.service.CollabSessionQueryService
import com.soma.wes.collab.service.CollabSessionService
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
    private val collabSessionService: CollabSessionService,
    private val collabSessionQueryService: CollabSessionQueryService,
) : CollabSessionControllerDocs {

    @PostMapping
    override fun open(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @RequestBody request: OpenCollabSessionRequest,
    ): ResponseEntity<CollabSessionResponse> {
        val result = collabSessionService.open(galleryId, loginUser.id, request)
        val status = HttpStatus.CREATED

        return ResponseEntity.status(status).body(result)
    }

    @GetMapping
    override fun list(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
    ): ResponseEntity<List<CollabSessionResponse>> {
        val result = collabSessionQueryService.list(galleryId, loginUser.id)

        return ResponseEntity.ok(result)
    }

    @GetMapping("/{sessionId}")
    override fun get(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable sessionId: Long,
    ): ResponseEntity<CollabSessionResponse> {
        val result = collabSessionQueryService.get(galleryId, sessionId, loginUser.id)

        return ResponseEntity.ok(result)
    }

    @PatchMapping("/{sessionId}")
    override fun rename(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable sessionId: Long,
        @RequestBody request: RenameCollabSessionRequest,
    ): ResponseEntity<CollabSessionResponse> {
        val result = collabSessionService.rename(galleryId, sessionId, loginUser.id, request)

        return ResponseEntity.ok(result)
    }

    @DeleteMapping("/{sessionId}")
    override fun revoke(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable sessionId: Long,
    ): ResponseEntity<Unit> {
        collabSessionService.revoke(galleryId, sessionId, loginUser.id)
        val status = HttpStatus.NO_CONTENT

        return ResponseEntity.status(status).build()
    }

    @PostMapping("/{sessionId}/republish")
    override fun republish(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable sessionId: Long,
    ): ResponseEntity<CollabSessionResponse> {
        val result = collabSessionService.republish(galleryId, sessionId, loginUser.id)

        return ResponseEntity.ok(result)
    }

    @PostMapping("/{sessionId}/photos")
    override fun addPhotos(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable sessionId: Long,
        @RequestBody request: AddCollabPhotosRequest,
    ): ResponseEntity<CollabPhotoPageResponse> {
        val result = collabSessionService.addPhotos(galleryId, sessionId, loginUser.id, request)

        return ResponseEntity.ok(result)
    }

    @DeleteMapping("/{sessionId}/photos")
    override fun removePhotos(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable sessionId: Long,
        @RequestBody request: RemoveCollabPhotosRequest,
    ): ResponseEntity<CollabPhotoPageResponse> {
        val result = collabSessionService.removePhotos(galleryId, sessionId, loginUser.id, request)

        return ResponseEntity.ok(result)
    }

    @GetMapping("/{sessionId}/photos")
    override fun listPhotos(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable sessionId: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "200") size: Int,
    ): ResponseEntity<CollabPhotoPageResponse> {
        val result = collabSessionQueryService.listPhotos(galleryId, sessionId, loginUser.id, page, size)

        return ResponseEntity.ok(result)
    }

    @DeleteMapping("/{sessionId}/comments/{commentId}")
    override fun deleteComment(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable sessionId: Long,
        @PathVariable commentId: Long,
    ): ResponseEntity<Unit> {
        collabSessionService.deleteComment(galleryId, sessionId, commentId, loginUser.id)
        val status = HttpStatus.NO_CONTENT

        return ResponseEntity.status(status).build()
    }
}
