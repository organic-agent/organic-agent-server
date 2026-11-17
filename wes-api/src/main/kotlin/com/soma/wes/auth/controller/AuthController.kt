package com.soma.wes.auth.controller

import com.soma.wes.auth.controller.docs.AuthControllerDocs
import com.soma.wes.auth.dto.request.ReissueRequest
import com.soma.wes.auth.dto.request.ReviewLoginRequest
import com.soma.wes.auth.dto.response.LoginResponse
import com.soma.wes.auth.dto.response.ReissueResponse
import com.soma.wes.auth.service.AuthTokenService
import com.soma.wes.auth.service.ReviewLoginService
import com.soma.wes.auth.domain.LoginUser
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
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
    private val reviewLoginService: ReviewLoginService,
) : AuthControllerDocs {

    /**
     * access token이 만료됐을 때 refresh token으로 새 access token을 받는다.
     */
    @PostMapping("/reissue")
    override fun reissue(
        @Valid @RequestBody request: ReissueRequest,
    ): ResponseEntity<ReissueResponse> {
        val result = authTokenService.reissue(request)

        return ResponseEntity.ok(result)
    }

    /**
     * 심사용 링크의 코드로 정해 둔 심사용 개인 계정에 로그인한다. 소셜 로그인과 같은 토큰을 준다.
     */
    @PostMapping("/review-login")
    override fun reviewLogin(
        @Valid @RequestBody request: ReviewLoginRequest,
    ): ResponseEntity<LoginResponse> {
        return ResponseEntity.ok(reviewLoginService.login(request))
    }

    @PostMapping("/logout")
    override fun logout(
        @AuthenticationPrincipal loginUser: LoginUser,
    ): ResponseEntity<Unit> {
        authTokenService.logout(loginUser.id)
        return ResponseEntity.noContent().build()
    }
}
