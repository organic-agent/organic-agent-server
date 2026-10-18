package com.soma.wes.collab.dto.response

import com.soma.wes.global.page.PageResponse
import io.swagger.v3.oas.annotations.media.Schema

/**
 * 협업 세션 사진 한 페이지.
 *
 * `viewUrlTtlSeconds` 때문에 [PageResponse]를 그대로 쓰지 못한다. 나머지 네 필드는 같은 이름과
 * 같은 기준(0부터 세는 `page`)을 유지한다.
 */
@Schema(description = "협업 세션 사진 한 페이지")
data class CollabPhotoPageResponse(
    val page: Int,
    val size: Int,
    val totalCount: Long,
    val hasNext: Boolean,
    val contents: List<CollabPhotoResponse>,

    @field:Schema(
        description = "viewUrl이 살아 있는 시간(초). 프론트는 이 시간이 지나기 전에 목록을 다시 불러야 한다.",
    )
    val viewUrlTtlSeconds: Long,

    @field:Schema(
        description = "viewUrl 중 만료되는 서명 URL이 있으면 true — 프론트는 true일 때만 수명 전에 다시 부른다. 이 응답은 언제나 서명 URL이라 true다.",
    )
    val viewUrlsExpire: Boolean = true,
) {

    companion object {
        fun of(page: PageResponse<CollabPhotoResponse>, viewUrlTtlSeconds: Long) = CollabPhotoPageResponse(
            page = page.page,
            size = page.size,
            totalCount = page.totalCount,
            hasNext = page.hasNext,
            contents = page.contents,
            viewUrlTtlSeconds = viewUrlTtlSeconds,
        )
    }
}
