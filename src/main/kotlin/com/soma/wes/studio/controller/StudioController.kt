package com.soma.wes.studio.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.studio.controller.docs.StudioControllerDocs
import com.soma.wes.studio.dto.request.CreateStudioRequest
import com.soma.wes.studio.dto.request.UpdateStudioRequest
import com.soma.wes.studio.dto.response.GalleryUrlAvailabilityResponse
import com.soma.wes.studio.dto.response.StudioResponse
import com.soma.wes.studio.service.StudioService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/api/v1/studios")
class StudioController(
    private val studioService: StudioService,
) : StudioControllerDocs {


    @PostMapping
    override fun create(
        @AuthenticationPrincipal loginUser: LoginUser,
        @Valid @RequestBody request: CreateStudioRequest,
    ): ResponseEntity<StudioResponse> {
        val result = studioService.create(loginUser.id, request)
        val status = HttpStatus.CREATED

        return ResponseEntity.status(status).body(result)
    }

    @GetMapping("/gallery-url/availability")
    override fun checkGalleryUrl(
        @RequestParam galleryUrl: String,
    ): ResponseEntity<GalleryUrlAvailabilityResponse> {
        val result = studioService.checkGalleryUrl(galleryUrl)

        return ResponseEntity.ok(result)
    }

    @GetMapping
    fun listMine(
        @AuthenticationPrincipal loginUser: LoginUser,
    ): ResponseEntity<List<StudioResponse>> {
        return ResponseEntity.ok(studioService.listMine(loginUser.id))
    }

    @PatchMapping("/{workspaceId}")
    fun update(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable workspaceId: Long,
        @Valid @RequestBody request: UpdateStudioRequest,
    ): ResponseEntity<StudioResponse> {
        return ResponseEntity.ok(studioService.update(workspaceId, loginUser.id, request))
    }

    @GetMapping("/me")
    override fun getMyStudio(
        @AuthenticationPrincipal loginUser: LoginUser,
    ): ResponseEntity<StudioResponse> {
        val result = studioService.getMyStudio(loginUser.id)

        return ResponseEntity.ok(result)
    }

    @PatchMapping("/me")
    override fun updateMyStudio(
        @AuthenticationPrincipal loginUser: LoginUser,
        @Valid @RequestBody request: UpdateStudioRequest,
    ): ResponseEntity<StudioResponse> {
        val result = studioService.updateMyStudio(loginUser.id, request)

        return ResponseEntity.ok(result)
    }
}
