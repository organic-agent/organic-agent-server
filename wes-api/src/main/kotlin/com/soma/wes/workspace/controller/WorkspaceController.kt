package com.soma.wes.workspace.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.workspace.dto.response.WorkspaceResponse
import com.soma.wes.workspace.service.WorkspaceService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/workspaces")
class WorkspaceController(
    private val workspaceService: WorkspaceService,
) {
    @GetMapping
    fun listMine(
        @AuthenticationPrincipal loginUser: LoginUser,
    ): ResponseEntity<List<WorkspaceResponse>> {
        return ResponseEntity.ok(workspaceService.listMine(loginUser.id))
    }
}
