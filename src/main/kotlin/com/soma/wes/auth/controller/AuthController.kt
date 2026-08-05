package com.soma.wes.auth.controller

import com.soma.wes.auth.controller.docs.AuthControllerDocs
import com.soma.wes.auth.dto.request.ReissueRequest
import com.soma.wes.auth.dto.response.ReissueResponse
import com.soma.wes.auth.service.AuthTokenService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Swagger 애노테이션은 [AuthControllerDocs]에 있다.
 */
@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authTokenService: AuthTokenService,
) : AuthControllerDocs {

    /**
     * access token이 만료됐을 때 refresh token으로 새 access token을 받는다.
     */
    @PostMapping("/reissue")
    override fun reissue(
        @RequestBody request: ReissueRequest,
    ): ResponseEntity<ReissueResponse> {
        val result = authTokenService.reissue(request)

        return ResponseEntity.ok(result)
    }
}
