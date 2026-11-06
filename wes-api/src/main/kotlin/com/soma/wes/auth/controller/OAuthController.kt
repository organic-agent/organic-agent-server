package com.soma.wes.auth.controller

import com.soma.wes.auth.controller.docs.OAuthControllerDocs
import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.auth.dto.request.AuthCodeRequest
import com.soma.wes.auth.dto.response.LoginResponse
import com.soma.wes.auth.dto.response.LoginUrlResponse
import com.soma.wes.auth.service.OAuthLoginService
import com.soma.wes.auth.service.OAuthLoginUrlService
import jakarta.validation.Valid
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

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
        @RequestParam(required = false) inviteToken: String?,
    ): ResponseEntity<LoginUrlResponse> {
        val result = oAuthLoginUrlService.generateLoginUrl(provider, origin, inviteToken)

        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .body(result)
    }

    @PostMapping("/{provider}")
    override fun login(
        @PathVariable provider: String,
        @RequestHeader(HttpHeaders.ORIGIN, required = false) origin: String?,
        @Valid @RequestBody request: AuthCodeRequest,
    ): ResponseEntity<LoginResponse> {
        val result = oAuthLoginService.login(provider, request, origin)

        return ResponseEntity.ok(result)
    }
}
