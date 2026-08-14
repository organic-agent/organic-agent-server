package com.soma.wes.folder.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.folder.controller.docs.PhotoFolderGroupControllerDocs
import com.soma.wes.folder.dto.request.CreateFolderGroupRequest
import com.soma.wes.folder.dto.request.RenameFolderGroupRequest
import com.soma.wes.folder.dto.response.PhotoFolderGroupResponse
import com.soma.wes.folder.service.PhotoFolderGroupService
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
@RequestMapping("/api/v1/galleries/{galleryId}/folder-groups")
class PhotoFolderGroupController(
    private val photoFolderGroupService: PhotoFolderGroupService,
) : PhotoFolderGroupControllerDocs {

    @PostMapping
    override fun create(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @Valid @RequestBody request: CreateFolderGroupRequest,
    ): ResponseEntity<PhotoFolderGroupResponse> {
        val result = photoFolderGroupService.create(galleryId, loginUser.id, request)
        val status = HttpStatus.CREATED

        return ResponseEntity.status(status).body(result)
    }

    @GetMapping
    override fun list(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
    ): ResponseEntity<List<PhotoFolderGroupResponse>> {
        val result = photoFolderGroupService.list(galleryId, loginUser.id)

        return ResponseEntity.ok(result)
    }

    @GetMapping("/{groupId}")
    override fun get(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable groupId: Long,
    ): ResponseEntity<PhotoFolderGroupResponse> {
        val result = photoFolderGroupService.get(galleryId, groupId, loginUser.id)

        return ResponseEntity.ok(result)
    }

    @PatchMapping("/{groupId}")
    override fun rename(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable groupId: Long,
        @Valid @RequestBody request: RenameFolderGroupRequest,
    ): ResponseEntity<PhotoFolderGroupResponse> {
        val result = photoFolderGroupService.rename(galleryId, groupId, loginUser.id, request)

        return ResponseEntity.ok(result)
    }

    @DeleteMapping("/{groupId}")
    override fun delete(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @PathVariable groupId: Long,
    ): ResponseEntity<Unit> {
        photoFolderGroupService.delete(galleryId, groupId, loginUser.id)
        val status = HttpStatus.NO_CONTENT

        return ResponseEntity.status(status).build()
    }
}
