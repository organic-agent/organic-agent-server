package com.soma.wes.retouch.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.retouch.controller.docs.RetouchControllerDocs
import com.soma.wes.retouch.dto.request.AddRetouchPhotosRequest
import com.soma.wes.retouch.dto.request.UpdateRetouchPhotoRequest
import com.soma.wes.retouch.dto.response.IssueAnnotationUploadUrlResponse
import com.soma.wes.retouch.dto.response.RetouchOverviewResponse
import com.soma.wes.retouch.dto.response.RetouchPhotoResponse
import com.soma.wes.retouch.service.RetouchService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/api/v1/galleries/{galleryId}/retouch")
class RetouchController(
    private val retouchService: RetouchService,
) : RetouchControllerDocs {

    @GetMapping
    override fun get(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
    ): ResponseEntity<RetouchOverviewResponse> {
        val result = retouchService.get(galleryId, loginUser.id)

        return ResponseEntity.ok(result)
    }

    @PostMapping("/photos")
    override fun addPhotos(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @Valid @RequestBody request: AddRetouchPhotosRequest,
    ): ResponseEntity<RetouchOverviewResponse> {
        val result = retouchService.addPhotos(galleryId, loginUser.id, request)

        return ResponseEntity.ok(result)
    }

    @DeleteMapping("/photos/{photoId}")
    override fun removePhoto(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable photoId: Long,
    ): ResponseEntity<Unit> {
        retouchService.removePhoto(galleryId, photoId, loginUser.id)
        val status = HttpStatus.NO_CONTENT

        return ResponseEntity.status(status).build()
    }

    @PutMapping("/photos/{photoId}/request")
    override fun updatePhoto(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable photoId: Long,
        @Valid @RequestBody request: UpdateRetouchPhotoRequest,
    ): ResponseEntity<RetouchPhotoResponse> {
        val result = retouchService.updatePhoto(galleryId, photoId, loginUser.id, request)

        return ResponseEntity.ok(result)
    }

    @PostMapping("/annotations/upload-url")
    override fun issueAnnotationUploadUrl(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
    ): ResponseEntity<IssueAnnotationUploadUrlResponse> {
        val result = retouchService.issueAnnotationUploadUrl(galleryId, loginUser.id)

        return ResponseEntity.ok(result)
    }

    @PostMapping("/rounds/submit")
    override fun submitRound(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
    ): ResponseEntity<RetouchOverviewResponse> {
        val result = retouchService.submitRound(galleryId, loginUser.id)

        return ResponseEntity.ok(result)
    }
}
