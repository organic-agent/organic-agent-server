package com.soma.wes.gallery.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.gallery.controller.docs.PersonalGalleryControllerDocs
import com.soma.wes.gallery.dto.request.CreatePersonalGalleryRequest
import com.soma.wes.gallery.dto.request.UpdatePersonalGalleryRequest
import com.soma.wes.gallery.dto.response.GalleryResponse
import com.soma.wes.gallery.service.PersonalGalleryService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/galleries")
class PersonalGalleryController(private val service: PersonalGalleryService) : PersonalGalleryControllerDocs {
    @PostMapping("/personal")
    override fun create(@AuthenticationPrincipal loginUser: LoginUser, @Valid @RequestBody request: CreatePersonalGalleryRequest): ResponseEntity<GalleryResponse> {
        val status = HttpStatus.CREATED
        return ResponseEntity.status(status).body(service.create(loginUser.id, request))
    }
    @PatchMapping("/{galleryId}/personal")
    override fun update(@AuthenticationPrincipal loginUser: LoginUser, @PathVariable galleryId: Long, @Valid @RequestBody request: UpdatePersonalGalleryRequest): ResponseEntity<GalleryResponse> {
        return ResponseEntity.ok(service.update(galleryId, loginUser.id, request))
    }
}
