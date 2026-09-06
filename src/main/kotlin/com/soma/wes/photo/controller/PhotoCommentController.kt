package com.soma.wes.photo.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.global.page.PageResponse
import com.soma.wes.photo.controller.docs.PhotoCommentControllerDocs
import com.soma.wes.photo.dto.request.WritePhotoCommentRequest
import com.soma.wes.photo.dto.response.PhotoCommentResponse
import com.soma.wes.photo.service.PhotoCommentService
import jakarta.validation.Valid
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

@RestController
@RequestMapping("/api/v1/galleries/{galleryId}/photos/{photoId}/comments")
class PhotoCommentController(
    private val service: PhotoCommentService,
) : PhotoCommentControllerDocs {
    @GetMapping
    override fun list(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable photoId: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "50") size: Int,
    ): ResponseEntity<PageResponse<PhotoCommentResponse>> {
        val result = service.list(galleryId, photoId, loginUser.id, page, size)
        return ResponseEntity.ok(result)
    }

    @PostMapping
    override fun write(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable photoId: Long,
        @Valid @RequestBody request: WritePhotoCommentRequest,
    ): ResponseEntity<PhotoCommentResponse> {
        val result = service.write(galleryId, photoId, loginUser.id, request)
        val status = HttpStatus.CREATED
        return ResponseEntity.status(status).body(result)
    }

    @DeleteMapping("/{commentId}")
    override fun delete(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable photoId: Long,
        @PathVariable commentId: Long,
    ): ResponseEntity<Unit> {
        service.delete(galleryId, photoId, commentId, loginUser.id)
        val status = HttpStatus.NO_CONTENT
        return ResponseEntity.status(status).build()
    }
}
