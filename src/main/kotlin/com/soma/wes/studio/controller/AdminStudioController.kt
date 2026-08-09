package com.soma.wes.studio.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.studio.controller.docs.AdminStudioControllerDocs
import com.soma.wes.studio.dto.request.ExecuteStudioDeletionRequest
import com.soma.wes.studio.dto.response.StudioDeletionResponse
import com.soma.wes.studio.service.StudioDeletionService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID


@RestController
@RequestMapping("/api/v1/admin/studios")
class AdminStudioController(
    private val studioDeletionService: StudioDeletionService,
) : AdminStudioControllerDocs {

    @PostMapping("/{studioId}/hard-delete")
    override fun hardDelete(
        @PathVariable studioId: Long,
        @AuthenticationPrincipal loginUser: LoginUser,
        @RequestHeader("Idempotency-Key") requestId: UUID,
        @Valid @RequestBody request: ExecuteStudioDeletionRequest,
    ): ResponseEntity<StudioDeletionResponse> {
        val result = studioDeletionService.execute(studioId, loginUser.id, requestId, request)

        return ResponseEntity.ok(result)
    }
}
