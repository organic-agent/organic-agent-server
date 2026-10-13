package com.soma.wes.photo.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.photo.controller.docs.PhotoRatingControllerDocs
import com.soma.wes.photo.dto.request.RatePhotoRequest
import com.soma.wes.photo.dto.response.PhotoRatingResponse
import com.soma.wes.photo.service.PhotoRatingService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/api/v1/galleries/{galleryId}/photos/{photoId}/rating")
class PhotoRatingController(
    private val photoRatingService: PhotoRatingService,
) : PhotoRatingControllerDocs {

    @PutMapping
    override fun rate(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable photoId: Long,
        @Valid @RequestBody request: RatePhotoRequest,
    ): ResponseEntity<PhotoRatingResponse> {
        val result = photoRatingService.rate(galleryId, photoId, loginUser.id, request)

        return ResponseEntity.ok(result)
    }

    @DeleteMapping
    override fun clear(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable photoId: Long,
    ): ResponseEntity<Unit> {
        photoRatingService.clear(galleryId, photoId, loginUser.id)
        val status = HttpStatus.NO_CONTENT

        return ResponseEntity.status(status).build()
    }
}
