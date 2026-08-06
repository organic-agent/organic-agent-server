package com.soma.wes.embedding.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.embedding.controller.docs.EmbeddingControllerDocs
import com.soma.wes.embedding.dto.response.EmbeddingRunResponse
import com.soma.wes.embedding.service.EmbeddingService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController


@RestController
@RequestMapping("/api/v1/galleries/{galleryId}/embeddings")
class EmbeddingController(
    private val embeddingService: EmbeddingService,
) : EmbeddingControllerDocs {

    /**
     * 202를 돌려주는 것은 형식이 아니라 사실이다. 이 응답이 돌아온 시점에 계산은 아직
     * 시작도 하지 않았을 수 있다.
     */
    @PostMapping("/run")
    override fun run(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
        @RequestParam(defaultValue = "false") force: Boolean,
    ): ResponseEntity<EmbeddingRunResponse> {
        val result = embeddingService.run(galleryId, loginUser.id, force)
        val status = HttpStatus.ACCEPTED

        return ResponseEntity.status(status).body(result)
    }
}
