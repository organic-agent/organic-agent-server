package com.soma.wes.photo.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.photo.controller.docs.PhotoControllerDocs
import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.photo.dto.request.CompleteUploadRequest
import com.soma.wes.photo.dto.request.IssueUploadUrlsRequest
import com.soma.wes.photo.dto.response.IssueUploadUrlsResponse
import com.soma.wes.photo.dto.response.PhotoCountResponse
import com.soma.wes.photo.dto.response.PhotoDetailResponse
import com.soma.wes.photo.dto.response.PhotoPageResponse
import com.soma.wes.photo.dto.response.PhotoSummaryResponse
import com.soma.wes.photo.service.PhotoService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/api/v1/galleries/{galleryId}/photos")
class PhotoController(
    private val photoService: PhotoService,
) : PhotoControllerDocs {

    @PostMapping("/upload-urls")
    override fun issueUploadUrls(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @Valid @RequestBody request: IssueUploadUrlsRequest,
    ): ResponseEntity<IssueUploadUrlsResponse> {
        val result = photoService.issueUploadUrls(galleryId, loginUser.id, request)

        return ResponseEntity.ok(result)
    }

    @PostMapping("/complete")
    override fun completeUpload(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @Valid @RequestBody request: CompleteUploadRequest,
    ): ResponseEntity<PhotoCountResponse> {
        val result = photoService.completeUpload(galleryId, loginUser.id, request)

        return ResponseEntity.ok(result)
    }

    @GetMapping
    override fun list(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @RequestParam(required = false) status: PhotoStatus?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "200") size: Int,
    ): ResponseEntity<PhotoPageResponse> {
        val result = photoService.list(galleryId, loginUser.id, status, page, size)

        return ResponseEntity.ok(result)
    }

    @GetMapping("/{photoId}")
    override fun get(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable photoId: Long,
    ): ResponseEntity<PhotoDetailResponse> {
        val result = photoService.get(galleryId, photoId, loginUser.id)

        return ResponseEntity.ok(result)
    }

    @GetMapping("/summary")
    override fun summary(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
    ): ResponseEntity<PhotoSummaryResponse> {
        val result = photoService.summarize(galleryId, loginUser.id)

        return ResponseEntity.ok(result)
    }
}
