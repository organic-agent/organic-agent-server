package com.soma.wes.analysis.controller

import com.soma.wes.analysis.controller.docs.AnalysisControllerDocs
import com.soma.wes.analysis.dto.response.AnalysisJobResponse
import com.soma.wes.analysis.service.AnalysisService
import com.soma.wes.auth.domain.LoginUser
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/galleries/{galleryId}/ai-analysis")
class AnalysisController(
    private val analysisService: AnalysisService,
) : AnalysisControllerDocs {

    /** 202다 — 응답이 돌아온 시점에 잡은 만들어졌을 뿐 임베딩·점수는 스윕이 이어서 민다. 본문은 없다. */
    @PostMapping
    override fun request(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
    ): ResponseEntity<AnalysisJobResponse> {
        val result = analysisService.request(galleryId, loginUser.id)
        return ResponseEntity.accepted().body(result)
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
