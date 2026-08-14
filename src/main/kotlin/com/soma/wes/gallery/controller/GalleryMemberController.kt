package com.soma.wes.gallery.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.gallery.controller.docs.GalleryMemberControllerDocs
import com.soma.wes.gallery.dto.response.GalleryMemberResponse
import com.soma.wes.gallery.service.GalleryMemberService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/api/v1/galleries/{galleryId}/members")
class GalleryMemberController(
    private val galleryMemberService: GalleryMemberService,
) : GalleryMemberControllerDocs {

    @GetMapping
    override fun list(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
    ): ResponseEntity<List<GalleryMemberResponse>> {
        val result = galleryMemberService.list(galleryId, loginUser.id)

        return ResponseEntity.ok(result)
    }

    @DeleteMapping("/{memberId}")
    override fun remove(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable memberId: Long,
    ): ResponseEntity<Unit> {
        galleryMemberService.remove(galleryId, memberId, loginUser.id)
        val status = HttpStatus.NO_CONTENT

        return ResponseEntity.status(status).build()
    }
}
