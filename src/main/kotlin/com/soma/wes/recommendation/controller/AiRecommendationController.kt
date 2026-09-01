package com.soma.wes.recommendation.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.recommendation.controller.docs.AiRecommendationControllerDocs
import com.soma.wes.recommendation.dto.request.AiRecommendationRequest
import com.soma.wes.recommendation.dto.response.AiRecommendationListResponse
import com.soma.wes.recommendation.dto.response.AiSelectionJobResponse
import com.soma.wes.recommendation.service.AiRecommendationService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/galleries/{galleryId}/photo-selection/recommendations")
class AiRecommendationController(
    private val aiRecommendationService: AiRecommendationService,
) : AiRecommendationControllerDocs {

    /** 202다 — 응답이 돌아온 시점에 추천은 큐에 들어갔을 뿐 시작하지 않았다. */
    @PostMapping
    override fun request(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @Valid @RequestBody(required = false) request: AiRecommendationRequest?,
    ): ResponseEntity<AiSelectionJobResponse> {
        val result = aiRecommendationService.request(
            galleryId,
            loginUser.id,
            request ?: AiRecommendationRequest(),
        )
        val status = HttpStatus.ACCEPTED

        return ResponseEntity.status(status).body(result)
    }

    @GetMapping
    override fun list(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @RequestParam(required = false) folderId: Long?,
    ): ResponseEntity<AiRecommendationListResponse> {
        val result = aiRecommendationService.list(galleryId, loginUser.id, folderId)

        return ResponseEntity.ok(result)
    }
}
