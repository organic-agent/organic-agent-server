package com.soma.wes.folder.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.folder.controller.docs.FolderConfirmationControllerDocs
import com.soma.wes.folder.dto.response.ConceptFolderResponse
import com.soma.wes.folder.service.FolderConfirmationService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// [REFACTOR-RENAME 2026-09-27] FolderOrganization* → FolderConfirmation* (클래스 이름만 변경, 동작·REST 경로 동일)
@RestController
@RequestMapping("/api/v1/galleries/{galleryId}/folders")
class FolderConfirmationController(
    private val service: FolderConfirmationService
) : FolderConfirmationControllerDocs {

    // [REFACTOR-CONFIRM 2026-09-27] 메서드 save → confirm (REST 경로 /from-clusters 그대로)
    @PostMapping("/from-clusters")
    override fun confirm(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
    ): ResponseEntity<List<ConceptFolderResponse>> {
        return ResponseEntity.ok(service.confirm(galleryId, loginUser.id))
    }
}
