package com.soma.wes.photo.dto

import java.time.Duration

/** Set-Cookie 한 줄의 재료. HttpOnly·Secure·SameSite=Lax는 모든 미리보기 쿠키에 같아서 담지 않는다(컨트롤러가 붙인다). */
data class PreviewCookieDto(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val maxAge: Duration,
)
