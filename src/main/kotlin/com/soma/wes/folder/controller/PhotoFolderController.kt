package com.soma.wes.folder.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.folder.controller.docs.PhotoFolderControllerDocs
import com.soma.wes.folder.dto.request.AddPhotosRequest
import com.soma.wes.folder.dto.request.CreatePhotoFolderRequest
import com.soma.wes.folder.dto.request.RenamePhotoFolderRequest
import com.soma.wes.folder.dto.response.PhotoFolderDetailResponse
import com.soma.wes.folder.dto.response.PhotoFolderResponse
import com.soma.wes.folder.service.PhotoFolderService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/api/v1/galleries/{galleryId}/photo-folders")
class PhotoFolderController(
    private val photoFolderService: PhotoFolderService,
) : PhotoFolderControllerDocs {

    @PostMapping
    override fun create(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @Valid @RequestBody request: CreatePhotoFolderRequest,
    ): ResponseEntity<PhotoFolderDetailResponse> {
        val result = photoFolderService.create(galleryId, loginUser.id, request)
        val status = HttpStatus.CREATED

        return ResponseEntity.status(status).body(result)
    }

    @GetMapping
    override fun list(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
    ): ResponseEntity<List<PhotoFolderResponse>> {
        val result = photoFolderService.list(galleryId, loginUser.id)

        return ResponseEntity.ok(result)
    }

    @GetMapping("/{folderId}")
    override fun get(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable folderId: Long,
    ): ResponseEntity<PhotoFolderDetailResponse> {
        val result = photoFolderService.get(galleryId, folderId, loginUser.id)

        return ResponseEntity.ok(result)
    }

    @PatchMapping("/{folderId}")
    override fun rename(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable folderId: Long,
        @Valid @RequestBody request: RenamePhotoFolderRequest,
    ): ResponseEntity<PhotoFolderResponse> {
        val result = photoFolderService.rename(galleryId, folderId, loginUser.id, request)

        return ResponseEntity.ok(result)
    }

    @DeleteMapping("/{folderId}")
    override fun delete(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable folderId: Long,
    ): ResponseEntity<Unit> {
        photoFolderService.delete(galleryId, folderId, loginUser.id)
        val status = HttpStatus.NO_CONTENT

        return ResponseEntity.status(status).build()
    }

    @PostMapping("/{folderId}/photos")
    override fun addPhotos(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable folderId: Long,
        @Valid @RequestBody request: AddPhotosRequest,
    ): ResponseEntity<PhotoFolderDetailResponse> {
        val result = photoFolderService.addPhotos(galleryId, folderId, loginUser.id, request)

        return ResponseEntity.ok(result)
    }

    @DeleteMapping("/{folderId}/photos/{photoId}")
    override fun removePhoto(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable folderId: Long,
        @PathVariable photoId: Long,
    ): ResponseEntity<Unit> {
        photoFolderService.removePhoto(galleryId, folderId, photoId, loginUser.id)
        val status = HttpStatus.NO_CONTENT

        return ResponseEntity.status(status).build()
    }
}
