package com.soma.wes.photo.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.photo.controller.docs.PhotoMemoControllerDocs
import com.soma.wes.photo.dto.request.WritePhotoMemoRequest
import com.soma.wes.photo.dto.response.PhotoMemoResponse
import com.soma.wes.photo.service.PhotoMemoService
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
@RequestMapping("/api/v1/galleries/{galleryId}/photos/{photoId}/memo")
class PhotoMemoController(
    private val photoMemoService: PhotoMemoService,
) : PhotoMemoControllerDocs {

    @PutMapping
    override fun write(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable photoId: Long,
        @Valid @RequestBody request: WritePhotoMemoRequest,
    ): ResponseEntity<PhotoMemoResponse> {
        val result = photoMemoService.write(galleryId, photoId, loginUser.id, request)

        return ResponseEntity.ok(result)
    }

    @DeleteMapping
    override fun clear(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable photoId: Long,
    ): ResponseEntity<Unit> {
        photoMemoService.clear(galleryId, photoId, loginUser.id)
        val status = HttpStatus.NO_CONTENT

        return ResponseEntity.status(status).build()
    }
}
