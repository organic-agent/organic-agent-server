package com.soma.wes.trash.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.trash.controller.docs.TrashControllerDocs
import com.soma.wes.trash.dto.request.EraseTrashedPhotosRequest
import com.soma.wes.trash.dto.request.RestorePhotosRequest
import com.soma.wes.trash.dto.response.TrashedGalleryResponse
import com.soma.wes.trash.dto.response.TrashedPhotoListResponse
import com.soma.wes.trash.service.TrashService
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
import org.springframework.web.bind.annotation.RestController

/**
 * 갤러리 휴지통과 사진 휴지통은 경로의 뿌리가 다르다. 사진 휴지통은 살아 있는 갤러리의
 * 하위 화면이라 `/galleries/{galleryId}/photos/trash`에 두지만, 갤러리 휴지통의 대상은
 * 숨어 있는 갤러리라 그 하위에 둘 수 없어 스튜디오 단위 화면인 `/trash/galleries`에 둔다.
 * 그래서 [com.soma.wes.gallery.controller.GalleryInviteController]처럼 `/api/v1`에 매핑한다.
 */
@RestController
@RequestMapping("/api/v1")
class TrashController(
    private val trashService: TrashService,
) : TrashControllerDocs {

    @GetMapping("/trash/galleries")
    override fun listGalleries(
        @AuthenticationPrincipal loginUser: LoginUser,
    ): ResponseEntity<List<TrashedGalleryResponse>> {
        val result = trashService.listGalleries(loginUser.id)

        return ResponseEntity.ok(result)
    }

    @PostMapping("/trash/galleries/{galleryId}/restore")
    override fun restoreGallery(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
    ): ResponseEntity<Unit> {
        trashService.restoreGallery(galleryId, loginUser.id)
        val status = HttpStatus.NO_CONTENT

        return ResponseEntity.status(status).build()
    }

    @DeleteMapping("/trash/galleries/{galleryId}")
    override fun eraseGallery(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
    ): ResponseEntity<Unit> {
        trashService.eraseGallery(galleryId, loginUser.id)
        val status = HttpStatus.NO_CONTENT

        return ResponseEntity.status(status).build()
    }

    @GetMapping("/galleries/{galleryId}/photos/trash")
    override fun listPhotos(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
    ): ResponseEntity<TrashedPhotoListResponse> {
        val result = trashService.listPhotos(galleryId, loginUser.id)

        return ResponseEntity.ok(result)
    }

    @PostMapping("/galleries/{galleryId}/photos/trash/restore")
    override fun restorePhotos(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @Valid @RequestBody request: RestorePhotosRequest,
    ): ResponseEntity<Unit> {
        trashService.restorePhotos(galleryId, loginUser.id, request)
        val status = HttpStatus.NO_CONTENT

        return ResponseEntity.status(status).build()
    }

    @DeleteMapping("/galleries/{galleryId}/photos/trash")
    override fun erasePhotos(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @Valid @RequestBody request: EraseTrashedPhotosRequest,
    ): ResponseEntity<Unit> {
        trashService.erasePhotos(galleryId, loginUser.id, request)
        val status = HttpStatus.NO_CONTENT

        return ResponseEntity.status(status).build()
    }
}
