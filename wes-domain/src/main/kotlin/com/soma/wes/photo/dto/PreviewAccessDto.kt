package com.soma.wes.photo.dto

import java.time.Instant

/**
 * 미리보기 접근 쿠키 묶음. CDN이 꺼져 있으면 [cookies]가 비고 [expiresAt]이 null이다 — 웹이 따로 분기하지 않게 같은 모양으로 돌려준다.
 */
data class PreviewAccessDto(
    val cookies: List<PreviewCookieDto>,
    val expiresAt: Instant?,
    val refreshAfterSeconds: Long?,
) {

    companion object {
        val DISABLED = PreviewAccessDto(cookies = emptyList(), expiresAt = null, refreshAfterSeconds = null)
    }
}
