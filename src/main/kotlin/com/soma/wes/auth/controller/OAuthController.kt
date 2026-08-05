package com.soma.wes.auth.controller

import com.soma.wes.auth.controller.docs.OAuthControllerDocs
import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.dto.request.AuthCodeRequest
import com.soma.wes.auth.dto.response.LoginResponse
import com.soma.wes.auth.dto.response.LoginUrlResponse
import com.soma.wes.auth.service.oauth.OAuthLoginService
import com.soma.wes.auth.service.oauth.OAuthLoginUrlService
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*


@RestController
@RequestMapping("/api/v1/oauth")
class OAuthController(
    private val oAuthLoginUrlService: OAuthLoginUrlService,
    private val oAuthLoginService: OAuthLoginService,
) : OAuthControllerDocs {


    @GetMapping("/login-url/{provider}")
    override fun loginUrl(
        @PathVariable provider: String,
        @RequestHeader(HttpHeaders.ORIGIN, required = false) origin: String?,
    ): ResponseEntity<LoginUrlResponse> {
        val result = oAuthLoginUrlService.generateLoginUrl(provider, origin)

        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(result)
    }

    @PostMapping("/{provider}")
    override fun login(
        @PathVariable provider: String,
        @RequestBody request: AuthCodeRequest,
        @RequestHeader(HttpHeaders.ORIGIN, required = false) origin: String?,
    ): ResponseEntity<LoginResponse> {
        val result = oAuthLoginService.login(provider, request, origin)

        return ResponseEntity.ok(result)
    }
}
