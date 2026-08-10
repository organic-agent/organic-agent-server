package com.soma.wes.collab.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.collab.controller.docs.CollabSessionControllerDocs
import com.soma.wes.collab.dto.request.AddCollabPhotosRequest
import com.soma.wes.collab.dto.request.RemoveCollabPhotosRequest
import com.soma.wes.collab.dto.response.CollabPhotoPageResponse
import com.soma.wes.collab.dto.response.CollabSessionResponse
import com.soma.wes.collab.service.CollabSessionService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController


/**
 * 부부와 담당 작가가 협업 세션을 다루는 경로. 전부 로그인이 필요하다.
 *
 * 하객이 부르는 경로는 [CollabShareController]·[CollabFeedbackController]로 갈라져
 * `/api/v1/collab/{shareToken}` 아래에 있다 — 경로가 갈려 있어야 무엇을 공개했는지가
 * [com.soma.wes.security.PublicPaths] 목록만 보고도 분명해진다.
 */
@RestController
@RequestMapping("/api/v1/galleries/{galleryId}/collab-session")
class CollabSessionController(
    private val collabSessionService: CollabSessionService,
) : CollabSessionControllerDocs {

    @PostMapping
    override fun open(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
    ): ResponseEntity<CollabSessionResponse> {
        val result = collabSessionService.open(galleryId, loginUser.id)

        return ResponseEntity.ok(result)
    }

    @GetMapping
    override fun get(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
    ): ResponseEntity<CollabSessionResponse> {
        val result = collabSessionService.get(galleryId, loginUser.id)

        return ResponseEntity.ok(result)
    }

    @DeleteMapping
    override fun revoke(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
    ): ResponseEntity<Unit> {
        collabSessionService.revoke(galleryId, loginUser.id)
        val status = HttpStatus.NO_CONTENT

        return ResponseEntity.status(status).build()
    }

    @PostMapping("/photos")
    override fun addPhotos(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @RequestBody request: AddCollabPhotosRequest,
    ): ResponseEntity<CollabPhotoPageResponse> {
        val result = collabSessionService.addPhotos(galleryId, loginUser.id, request)

        return ResponseEntity.ok(result)
    }

    @DeleteMapping("/photos")
    override fun removePhotos(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @RequestBody request: RemoveCollabPhotosRequest,
    ): ResponseEntity<CollabPhotoPageResponse> {
        val result = collabSessionService.removePhotos(galleryId, loginUser.id, request)

        return ResponseEntity.ok(result)
    }

    @GetMapping("/photos")
    override fun listPhotos(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "200") size: Int,
    ): ResponseEntity<CollabPhotoPageResponse> {
        val result = collabSessionService.listPhotos(galleryId, loginUser.id, page, size)

        return ResponseEntity.ok(result)
    }

    @DeleteMapping("/comments/{commentId}")
    override fun deleteComment(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable commentId: Long,
    ): ResponseEntity<Unit> {
        collabSessionService.deleteComment(galleryId, commentId, loginUser.id)
        val status = HttpStatus.NO_CONTENT

        return ResponseEntity.status(status).build()
    }
}
