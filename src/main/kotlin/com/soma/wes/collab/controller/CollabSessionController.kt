package com.soma.wes.collab.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.collab.controller.docs.CollabSessionControllerDocs
import com.soma.wes.collab.dto.request.AddCollabPhotosRequest
import com.soma.wes.collab.dto.request.OpenCollabSessionRequest
import com.soma.wes.collab.dto.request.RemoveCollabPhotosRequest
import com.soma.wes.collab.dto.request.RenameCollabSessionRequest
import com.soma.wes.collab.dto.response.CollabPhotoPageResponse
import com.soma.wes.collab.dto.response.CollabSessionResponse
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


/**
 * 부부와 담당 작가가 협업 세션을 다루는 경로. 전부 로그인이 필요하다.
 *
 * 갤러리 하나에 세션이 여럿이라 경로가 `collab-sessions/{sessionId}`로 갈린다. 부부는 묶음마다
 * 물어볼 상대가 달라서(본식 후보는 부모님께, 2부 사진은 친구들에게) 링크도 그만큼 나온다.
 *
 * 하객이 부르는 경로는 [CollabShareController]·[CollabFeedbackController]로 갈라져
 * `/api/v1/collab/{shareToken}` 아래에 있다 — 경로가 갈려 있어야 무엇을 공개했는지가
 * [com.soma.wes.security.PublicPaths] 목록만 보고도 분명해진다. 그쪽에는 `sessionId`가 없다.
 * 토큰 하나가 세션을 유일하게 지목하므로, 하객은 자기가 받은 링크의 사진만 보게 된다.
 */
@RestController
@RequestMapping("/api/v1/galleries/{galleryId}/collab-sessions")
class CollabSessionController(
    private val collabSessionService: CollabSessionService,
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
        val result = collabSessionService.list(galleryId, loginUser.id)

        return ResponseEntity.ok(result)
    }

    @GetMapping("/{sessionId}")
    override fun get(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable sessionId: Long,
    ): ResponseEntity<CollabSessionResponse> {
        val result = collabSessionService.get(galleryId, sessionId, loginUser.id)

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

    @PostMapping("/{sessionId}/share-token")
    override fun reissueToken(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable sessionId: Long,
    ): ResponseEntity<CollabSessionResponse> {
        val result = collabSessionService.reissueToken(galleryId, sessionId, loginUser.id)

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
        val result = collabSessionService.listPhotos(galleryId, sessionId, loginUser.id, page, size)

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
