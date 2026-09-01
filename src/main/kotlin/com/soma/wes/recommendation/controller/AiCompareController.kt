package com.soma.wes.recommendation.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.recommendation.controller.docs.AiCompareControllerDocs
import com.soma.wes.recommendation.dto.request.ComparePhotosRequest
import com.soma.wes.recommendation.dto.response.PairVerdictResponse
import com.soma.wes.recommendation.service.AiCompareService
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/galleries/{galleryId}/photo-selection/compare")
class AiCompareController(
    private val aiCompareService: AiCompareService,
) : AiCompareControllerDocs {

    /** 동기다 — 200이 곧 판정이다. 추천(202 큐잉)과 달리 여기서는 사용자가 기다린다. */
    @PostMapping
    override fun compare(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @Valid @RequestBody request: ComparePhotosRequest,
    ): ResponseEntity<PairVerdictResponse> {
        val result = aiCompareService.compare(galleryId, loginUser.id, request)

        return ResponseEntity.ok(result)
    }
}
