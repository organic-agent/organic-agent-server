package com.soma.wes.gallery.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.gallery.controller.docs.GalleryInviteControllerDocs
import com.soma.wes.gallery.dto.response.GalleryInviteAcceptResponse
import com.soma.wes.gallery.dto.response.GalleryInviteResponse
import com.soma.wes.gallery.dto.response.GalleryInvitePreviewResponse
import com.soma.wes.gallery.dto.request.IssueGalleryInviteRequest
import com.soma.wes.gallery.service.GalleryInviteService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController


/**
 * 다른 컨트롤러와 달리 `/api/v1`에 매핑한다. 수락은 갤러리 하위 경로에 둘 수 없다 —
 * 링크에는 토큰만 실려 있어서, 누르는 사람은 자기가 어느 갤러리로 가는지 모른다.
 */
@RestController
@RequestMapping("/api/v1")
class GalleryInviteController(
    private val galleryInviteService: GalleryInviteService,
) : GalleryInviteControllerDocs {

    @PostMapping("/galleries/{galleryId}/invites")
    override fun issue(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @Valid @RequestBody(required = false) request: IssueGalleryInviteRequest?,
    ): ResponseEntity<GalleryInviteResponse> {
        val result = galleryInviteService.issue(galleryId, loginUser.id, request ?: IssueGalleryInviteRequest())
        val status = HttpStatus.CREATED

        return ResponseEntity.status(status).body(result)
    }

    @GetMapping("/galleries/{galleryId}/invite")
    override fun getCurrent(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
    ): ResponseEntity<GalleryInviteResponse> {
        val result = galleryInviteService.getCurrent(galleryId, loginUser.id)

        return ResponseEntity.ok(result)
    }

    @DeleteMapping("/galleries/{galleryId}/invites/{inviteId}")
    override fun revoke(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable inviteId: Long,
    ): ResponseEntity<Unit> {
        galleryInviteService.revoke(galleryId, inviteId, loginUser.id)
        val status = HttpStatus.NO_CONTENT

        return ResponseEntity.status(status).build()
    }

    @PostMapping("/invites/{token}/accept")
    override fun accept(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable token: String,
    ): ResponseEntity<GalleryInviteAcceptResponse> {
        val result = galleryInviteService.accept(token, loginUser.id)

        return ResponseEntity.ok(result)
    }

    @GetMapping("/invites/{token}")
    override fun preview(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable token: String,
    ): ResponseEntity<GalleryInvitePreviewResponse> =
        ResponseEntity.ok(galleryInviteService.preview(token, loginUser.id))
}
