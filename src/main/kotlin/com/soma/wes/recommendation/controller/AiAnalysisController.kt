package com.soma.wes.recommendation.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.recommendation.controller.docs.AiAnalysisControllerDocs
import com.soma.wes.recommendation.domain.AiAnalysisMode
import com.soma.wes.recommendation.dto.request.AiAnalysisRequest
import com.soma.wes.recommendation.dto.response.AiAnalysisJobResponse
import com.soma.wes.recommendation.service.AiAnalysisService
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
class AiAnalysisController(
    private val aiAnalysisService: AiAnalysisService,
) : AiAnalysisControllerDocs {

    /** 202다 — 응답이 돌아온 시점에 분석은 큐에 들어갔을 뿐 시작하지 않았다. */
    @PostMapping
    override fun request(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @Valid @RequestBody(required = false) request: AiAnalysisRequest?,
    ): ResponseEntity<AiAnalysisJobResponse> {
        val mode = request?.mode ?: AiAnalysisMode.FULL
        val result = aiAnalysisService.request(galleryId, loginUser.id, mode)
        val status = HttpStatus.ACCEPTED

        return ResponseEntity.status(status).body(result)
    }

    @GetMapping
    override fun latest(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
    ): ResponseEntity<AiAnalysisJobResponse> {
        val result = aiAnalysisService.latest(galleryId, loginUser.id)

        return ResponseEntity.ok(result)
    }
}
