package com.soma.wes.studio.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.studio.controller.docs.StudioControllerDocs
import com.soma.wes.studio.dto.request.CreateStudioRequest
import com.soma.wes.studio.dto.request.UpdateStudioRequest
import com.soma.wes.studio.dto.request.ChangeStudioMemberRoleRequest
import com.soma.wes.studio.dto.response.GalleryUrlAvailabilityResponse
import com.soma.wes.studio.dto.response.StudioResponse
import com.soma.wes.studio.dto.response.StudioMemberResponse
import com.soma.wes.studio.service.StudioService
import com.soma.wes.studio.service.StudioInviteService
import com.soma.wes.studio.dto.response.StudioInviteResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
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
    private val studioInviteService: StudioInviteService,
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

    @GetMapping("/{workspaceId}")
    override fun get(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable workspaceId: Long,
    ): ResponseEntity<StudioResponse> {
        return ResponseEntity.ok(studioService.get(workspaceId, loginUser.id))
    }

    @PostMapping("/{workspaceId}/invite-link", "/{workspaceId}/invites")
    override fun issueInvite(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable workspaceId: Long,
    ): ResponseEntity<StudioInviteResponse> {
        return ResponseEntity.status(HttpStatus.CREATED).body(studioInviteService.issue(workspaceId, loginUser.id))
    }

    @GetMapping("/{workspaceId}/invite-link")
    override fun currentInvite(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable workspaceId: Long,
    ): ResponseEntity<StudioInviteResponse> {
        return ResponseEntity.ok(studioInviteService.current(workspaceId, loginUser.id))
    }

    @DeleteMapping("/{workspaceId}")
    override fun delete(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable workspaceId: Long,
    ): ResponseEntity<Unit> {
        studioService.delete(workspaceId, loginUser.id)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{workspaceId}/members/{memberId}")
    override fun removeMember(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable workspaceId: Long,
        @PathVariable memberId: Long,
    ): ResponseEntity<Unit> {
        studioService.removeMember(workspaceId, memberId, loginUser.id)
        return ResponseEntity.noContent().build()
    }

    @PatchMapping("/{workspaceId}")
    fun update(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable workspaceId: Long,
        @Valid @RequestBody request: UpdateStudioRequest,
    ): ResponseEntity<StudioResponse> {
        return ResponseEntity.ok(studioService.update(workspaceId, loginUser.id, request))
    }

    @GetMapping("/{workspaceId}/members")
    fun listMembers(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable workspaceId: Long,
    ): ResponseEntity<List<StudioMemberResponse>> =
        ResponseEntity.ok(studioService.listMembers(workspaceId, loginUser.id))

    @PatchMapping("/{workspaceId}/members/{memberId}/role")
    fun changeMemberRole(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable workspaceId: Long,
        @PathVariable memberId: Long,
        @Valid @RequestBody request: ChangeStudioMemberRoleRequest,
    ): ResponseEntity<StudioMemberResponse> =
        ResponseEntity.ok(studioService.changeMemberRole(workspaceId, memberId, loginUser.id, request))

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

    @DeleteMapping("/{workspaceId}/members/me")
    override fun leave(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable workspaceId: Long,
    ): ResponseEntity<Unit> {
        studioService.leave(workspaceId, loginUser.id)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/me")
    override fun deleteMyStudio(
        @AuthenticationPrincipal loginUser: LoginUser,
    ): ResponseEntity<Unit> {
        studioService.deleteMyStudio(loginUser.id)
        return ResponseEntity.noContent().build()
    }
}
