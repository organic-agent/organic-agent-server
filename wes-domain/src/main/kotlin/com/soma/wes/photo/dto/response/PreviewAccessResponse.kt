package com.soma.wes.photo.dto.response

import com.soma.wes.photo.dto.PreviewAccessDto
import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

@Schema(description = "미리보기 접근 쿠키의 수명. 쿠키 자체는 Set-Cookie 헤더로 온다(HttpOnly라 스크립트가 읽지 못한다).")
data class PreviewAccessResponse(
    @field:Schema(
        description = "쿠키가 끝나는 시각. CDN이 꺼져 있으면 null — 그때 미리보기는 서명 URL이고 쿠키가 필요 없다.",
        example = "2026-10-19T03:20:00Z",
    )
    val expiresAt: Instant?,

    @field:Schema(
        description = "이 시간(초)이 지나면 다시 부른다. 목록은 다시 읽지 않는다. CDN이 꺼져 있으면 null.",
        example = "600",
    )
    val refreshAfterSeconds: Long?,
) {

    companion object {
        fun from(access: PreviewAccessDto) = PreviewAccessResponse(
            expiresAt = access.expiresAt,
            refreshAfterSeconds = access.refreshAfterSeconds,
        )
    }
}
