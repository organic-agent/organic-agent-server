package com.soma.wes.category.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.category.controller.docs.FolderOrganizationControllerDocs
import com.soma.wes.category.dto.response.ConceptFolderResponse
import com.soma.wes.category.service.FolderOrganizationService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/galleries/{galleryId}/folders")
class FolderOrganizationController(
    private val service: FolderOrganizationService
) : FolderOrganizationControllerDocs {

    @PostMapping("/from-clusters")
    override fun save(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
    ): ResponseEntity<List<ConceptFolderResponse>> {
        return ResponseEntity.ok(service.save(galleryId, loginUser.id))
    }
}
