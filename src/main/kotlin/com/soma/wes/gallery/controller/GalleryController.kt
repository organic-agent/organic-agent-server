package com.soma.wes.gallery.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.gallery.controller.docs.GalleryControllerDocs
import com.soma.wes.gallery.dto.request.ChangeTargetPhotoCountRequest
import com.soma.wes.gallery.dto.request.CreateGalleryRequest
import com.soma.wes.gallery.dto.request.ReopenGalleryRequest
import com.soma.wes.gallery.dto.response.GalleryResponse
import com.soma.wes.gallery.service.GalleryService
import com.soma.wes.gallery.service.MockGalleryService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/api/v1/galleries")
class GalleryController(
    private val galleryService: GalleryService,
    private val mockGalleryService: MockGalleryService,
) : GalleryControllerDocs {

    @PostMapping
    override fun create(
        @AuthenticationPrincipal loginUser: LoginUser,
        @Valid @RequestBody request: CreateGalleryRequest,
    ): ResponseEntity<GalleryResponse> {
        val result = galleryService.create(loginUser.id, request)
        val status = HttpStatus.CREATED

        return ResponseEntity.status(status).body(result)
    }

    @PostMapping("/mock")
    override fun createMock(
        @AuthenticationPrincipal loginUser: LoginUser,
        @Valid @RequestBody(required = false) request: CreateGalleryRequest?,
    ): ResponseEntity<GalleryResponse> {
        val result = mockGalleryService.create(loginUser.id, request)

        return ResponseEntity.ok(result)
    }

    @GetMapping
    override fun list(
        @AuthenticationPrincipal loginUser: LoginUser,
    ): ResponseEntity<List<GalleryResponse>> {
        val result = galleryService.findAllVisibleTo(loginUser.id)

        return ResponseEntity.ok(result)
    }

    @GetMapping("/{galleryId}")
    override fun get(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
    ): ResponseEntity<GalleryResponse> {
        val result = galleryService.get(galleryId, loginUser.id)

        return ResponseEntity.ok(result)
    }

    @PatchMapping("/{galleryId}/target-photo-count")
    override fun changeTargetPhotoCount(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @Valid @RequestBody request: ChangeTargetPhotoCountRequest,
    ): ResponseEntity<GalleryResponse> {
        val result = galleryService.changeTargetPhotoCount(galleryId, loginUser.id, request)

        return ResponseEntity.ok(result)
    }

    @PostMapping("/{galleryId}/open")
    override fun open(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
    ): ResponseEntity<GalleryResponse> {
        val result = galleryService.open(galleryId, loginUser.id)

        return ResponseEntity.ok(result)
    }

    @PostMapping("/{galleryId}/close")
    override fun close(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
    ): ResponseEntity<GalleryResponse> {
        val result = galleryService.close(galleryId, loginUser.id)

        return ResponseEntity.ok(result)
    }

    @PostMapping("/{galleryId}/reopen")
    override fun reopen(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @RequestBody request: ReopenGalleryRequest,
    ): ResponseEntity<GalleryResponse> {
        val result = galleryService.reopen(galleryId, loginUser.id, request)

        return ResponseEntity.ok(result)
    }
}
