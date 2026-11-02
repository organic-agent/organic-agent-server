package com.soma.wes.selection.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.selection.controller.docs.SelectionSyncControllerDocs
import com.soma.wes.selection.dto.response.SelectionSyncResponse
import com.soma.wes.selection.service.SelectionSyncService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/galleries/{galleryId}/selection-sync")
class SelectionSyncController(
    private val selectionSyncService: SelectionSyncService,
) : SelectionSyncControllerDocs {
    @GetMapping
    override fun get(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
    ): ResponseEntity<SelectionSyncResponse> {
        val result = selectionSyncService.get(galleryId = galleryId, userId = loginUser.id)
        return ResponseEntity.ok(result)
    }
}
