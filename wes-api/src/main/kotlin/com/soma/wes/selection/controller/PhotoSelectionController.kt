package com.soma.wes.selection.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.selection.service.PhotoSelectionExportService
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import com.soma.wes.retouch.dto.request.SubmitRetouchRequestsRequest
import com.soma.wes.selection.controller.docs.PhotoSelectionControllerDocs
import com.soma.wes.selection.dto.request.DeselectPhotosRequest
import com.soma.wes.selection.dto.request.SelectPhotosRequest
import com.soma.wes.selection.dto.response.PhotoSelectionResponse
import com.soma.wes.selection.service.PhotoSelectionService
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


@RestController
@RequestMapping("/api/v1/galleries/{galleryId}/photo-selection")
class PhotoSelectionController(
    private val photoSelectionService: PhotoSelectionService,
    private val photoSelectionExportService: PhotoSelectionExportService,
) : PhotoSelectionControllerDocs {

    @GetMapping
    override fun get(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
    ): ResponseEntity<PhotoSelectionResponse> {
        val result = photoSelectionService.get(galleryId, loginUser.id)

        return ResponseEntity.ok(result)
    }

    @PostMapping("/photos")
    override fun select(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @Valid @RequestBody request: SelectPhotosRequest,
    ): ResponseEntity<PhotoSelectionResponse> {
        val result = photoSelectionService.select(galleryId, loginUser.id, request)

        return ResponseEntity.ok(result)
    }

    @DeleteMapping("/photos")
    override fun deselect(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @Valid @RequestBody request: DeselectPhotosRequest,
    ): ResponseEntity<PhotoSelectionResponse> {
        val result = photoSelectionService.deselect(galleryId, loginUser.id, request)

        return ResponseEntity.ok(result)
    }

    @DeleteMapping("/photos/{photoId}")
    override fun deselectPhoto(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable photoId: Long,
    ): ResponseEntity<Unit> {
        photoSelectionService.deselectPhoto(galleryId, photoId, loginUser.id)
        val status = HttpStatus.NO_CONTENT

        return ResponseEntity.status(status).build()
    }

    @PostMapping("/submit")
    override fun submit(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @Valid @RequestBody(required = false) request: SubmitRetouchRequestsRequest?,
    ): ResponseEntity<PhotoSelectionResponse> {
        val result = photoSelectionService.submit(galleryId, loginUser.id, request ?: SubmitRetouchRequestsRequest())

        return ResponseEntity.ok(result)
    }

    @PostMapping("/withdraw")
    override fun withdraw(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
    ): ResponseEntity<PhotoSelectionResponse> {
        val result = photoSelectionService.withdraw(galleryId, loginUser.id)

        return ResponseEntity.ok(result)
    }
    @GetMapping("/export")
    override fun export(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
    ): ResponseEntity<ByteArray> {
        val result = photoSelectionExportService.export(galleryId, loginUser.id)
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=wes-selection-$galleryId.csv")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(result)
    }
}
