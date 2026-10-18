package com.soma.wes.photo.controller

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.photo.controller.docs.PreviewAccessControllerDocs
import com.soma.wes.photo.dto.PreviewCookieDto
import com.soma.wes.photo.dto.response.PreviewAccessResponse
import com.soma.wes.photo.service.PreviewAccessService
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/galleries/{galleryId}/image-access")
class PreviewAccessController(
    private val previewAccessService: PreviewAccessService,
) : PreviewAccessControllerDocs {

    @PostMapping
    override fun issue(
        @AuthenticationPrincipal loginUser: LoginUser,
        @PathVariable galleryId: Long,
    ): ResponseEntity<PreviewAccessResponse> {
        val result = previewAccessService.issue(galleryId = galleryId, userId = loginUser.id)

        val cookies = result.cookies.map { it.toSetCookie() }.toTypedArray()
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, *cookies)
            .body(PreviewAccessResponse.from(result))
    }

    /** 미리보기 쿠키는 모두 HttpOnly · Secure · SameSite=Lax다 — 스크립트가 읽지 못하고, 같은 사이트(www → img)에서만 실린다. */
    private fun PreviewCookieDto.toSetCookie(): String = ResponseCookie.from(name, value)
        .domain(domain)
        .path(path)
        .maxAge(maxAge)
        .httpOnly(true)
        .secure(true)
        .sameSite("Lax")
        .build()
        .toString()
}
