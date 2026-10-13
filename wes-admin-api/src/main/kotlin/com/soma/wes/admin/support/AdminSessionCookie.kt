package com.soma.wes.admin.support

import org.springframework.http.ResponseCookie
import java.time.Duration

object AdminSessionCookie {

    const val NAME = "__Host-wes_admin_session"

    fun create(
        rawSessionToken: String,
        maxAge: Duration,
    ): ResponseCookie =
        ResponseCookie.from(NAME, rawSessionToken)
            .httpOnly(true)
            .secure(true)
            .sameSite("Strict")
            .path("/")
            .maxAge(maxAge)
            .build()

    fun delete(): ResponseCookie =
        ResponseCookie.from(NAME, "")
            .httpOnly(true)
            .secure(true)
            .sameSite("Strict")
            .path("/")
            .maxAge(Duration.ZERO)
            .build()
}
