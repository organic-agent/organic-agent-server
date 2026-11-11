package com.soma.wes.album.controller

import com.soma.wes.album.controller.docs.AlbumControllerDocs
import com.soma.wes.album.dto.request.ConfirmAlbumRequest
import com.soma.wes.album.dto.request.ReturnAlbumRequest
import com.soma.wes.album.dto.request.SaveAlbumRequest
import com.soma.wes.album.dto.request.SendAlbumRequest
import com.soma.wes.album.dto.response.AlbumPhotosResponse
import com.soma.wes.album.dto.response.AlbumResponse
import com.soma.wes.album.service.AlbumService
import com.soma.wes.auth.domain.LoginUser
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/galleries/{galleryId}/album")
class AlbumController(private val service: AlbumService) : AlbumControllerDocs {
    @GetMapping
    override fun get(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
    ): ResponseEntity<AlbumResponse> {
        val result = service.get(galleryId, loginUser.id)
        return ResponseEntity.ok(result)
    }

    @GetMapping("/photos")
    override fun listPhotos(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
    ): ResponseEntity<AlbumPhotosResponse> {
        val result = service.listPhotos(galleryId, loginUser.id)
        return ResponseEntity.ok(result)
    }

    @PutMapping
    override fun saveStudio(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @Valid @RequestBody request: SaveAlbumRequest,
    ): ResponseEntity<AlbumResponse> {
        val result = service.saveStudio(galleryId, loginUser.id, request)
        return ResponseEntity.ok(result)
    }

    @PostMapping("/send")
    override fun send(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @Valid @RequestBody request: SendAlbumRequest,
    ): ResponseEntity<AlbumResponse> {
        val result = service.send(galleryId, loginUser.id, request)
        return ResponseEntity.ok(result)
    }

    @PutMapping("/client-copy")
    override fun saveClient(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @Valid @RequestBody request: SaveAlbumRequest,
    ): ResponseEntity<AlbumResponse> {
        val result = service.saveClient(galleryId, loginUser.id, request)
        return ResponseEntity.ok(result)
    }

    @PostMapping("/return")
    override fun returnToStudio(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @Valid @RequestBody request: ReturnAlbumRequest,
    ): ResponseEntity<AlbumResponse> {
        val result = service.returnToStudio(galleryId, loginUser.id, request)
        return ResponseEntity.ok(result)
    }

    @PostMapping("/confirm")
    override fun confirm(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @Valid @RequestBody request: ConfirmAlbumRequest,
    ): ResponseEntity<AlbumResponse> {
        val result = service.confirm(galleryId, loginUser.id, request)
        return ResponseEntity.ok(result)
    }
}
