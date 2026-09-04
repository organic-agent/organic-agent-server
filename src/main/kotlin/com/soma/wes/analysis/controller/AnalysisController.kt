package com.soma.wes.analysis.controller

import com.soma.wes.analysis.controller.docs.AnalysisControllerDocs
import com.soma.wes.analysis.dto.request.AnalysisRequest
import com.soma.wes.analysis.dto.response.AnalysisJobResponse
import com.soma.wes.analysis.service.AnalysisService
import com.soma.wes.auth.domain.LoginUser
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/galleries/{galleryId}/ai-analysis")
class AnalysisController(
    private val analysisService: AnalysisService,
) : AnalysisControllerDocs {

    /** 202다 — 응답이 돌아온 시점에 첫 단계는 큐에 들어갔을 뿐 시작하지 않았다. */
    @PostMapping
    override fun request(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @Valid @RequestBody(required = false) request: AnalysisRequest?,
    ): ResponseEntity<AnalysisJobResponse> {
        val body = request ?: AnalysisRequest()
        val result = analysisService.request(galleryId, loginUser.id, body.mode, body.force)
        val status = HttpStatus.ACCEPTED
        return ResponseEntity.status(status).body(result)
    }

    @GetMapping
    override fun latest(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
    ): ResponseEntity<AnalysisJobResponse> {
        val result = analysisService.latest(galleryId, loginUser.id)
        return ResponseEntity.ok(result)
    }
}
