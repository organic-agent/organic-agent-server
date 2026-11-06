package com.soma.wes.photo.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.photo.controller.docs.PhotoExclusionControllerDocs
import com.soma.wes.photo.dto.request.ChangePhotoExclusionsRequest
import com.soma.wes.photo.dto.response.PhotoCountResponse
import com.soma.wes.photo.dto.response.PhotoPageResponse
import com.soma.wes.photo.service.PhotoExclusionService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/galleries/{galleryId}/photos/exclusions")
class PhotoExclusionController(
    private val exclusions: PhotoExclusionService,
) : PhotoExclusionControllerDocs {
    @GetMapping
    override fun list(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "200") size: Int,
    ): ResponseEntity<PhotoPageResponse> {
        val result = exclusions.list(galleryId = galleryId, userId = loginUser.id, page = page, size = size)
        return ResponseEntity.ok(result)
    }

    @PutMapping
    override fun exclude(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @Valid @RequestBody request: ChangePhotoExclusionsRequest,
    ): ResponseEntity<PhotoCountResponse> {
        val result = exclusions.exclude(galleryId = galleryId, userId = loginUser.id, request = request)
        return ResponseEntity.ok(result)
    }

    @DeleteMapping
    override fun restore(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @Valid @RequestBody request: ChangePhotoExclusionsRequest,
    ): ResponseEntity<PhotoCountResponse> {
        val result = exclusions.restore(galleryId = galleryId, userId = loginUser.id, request = request)
        return ResponseEntity.ok(result)
    }
}
