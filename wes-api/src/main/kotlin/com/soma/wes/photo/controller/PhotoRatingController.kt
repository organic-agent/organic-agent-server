package com.soma.wes.photo.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.photo.controller.docs.PhotoRatingControllerDocs
import com.soma.wes.photo.dto.request.RatePhotoRequest
import com.soma.wes.photo.dto.response.PhotoRatingHistoryResponse
import com.soma.wes.photo.dto.response.PhotoRatingResponse
import com.soma.wes.photo.service.PhotoRatingService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/galleries/{galleryId}")
class PhotoRatingController(
    private val photoRatingService: PhotoRatingService,
) : PhotoRatingControllerDocs {

    @GetMapping("/photo-ratings")
    override fun list(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
    ): ResponseEntity<List<PhotoRatingResponse>> {
        val result = photoRatingService.list(galleryId = galleryId, userId = loginUser.id)
        return ResponseEntity.ok(result)
    }

    @PutMapping("/photos/{photoId}/rating")
    override fun rate(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable photoId: Long,
        @Valid @RequestBody request: RatePhotoRequest,
    ): ResponseEntity<PhotoRatingResponse> {
        val result = photoRatingService.rate(galleryId, photoId, loginUser.id, request)

        return ResponseEntity.ok(result)
    }

    @DeleteMapping("/photos/{photoId}/rating")
    override fun clear(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable photoId: Long,
    ): ResponseEntity<Unit> {
        photoRatingService.clear(galleryId, photoId, loginUser.id)
        val status = HttpStatus.NO_CONTENT

        return ResponseEntity.status(status).build()
    }

    @GetMapping("/photos/{photoId}/rating-history")
    override fun history(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable photoId: Long,
    ): ResponseEntity<List<PhotoRatingHistoryResponse>> {
        val result = photoRatingService.history(galleryId, photoId, loginUser.id)

        return ResponseEntity.ok(result)
    }
}
