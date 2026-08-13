package com.soma.wes.photo.dto.response

import com.soma.wes.global.page.PageResponse
import io.swagger.v3.oas.annotations.media.Schema

/**
 * 사진 목록 한 페이지.
 *
 * [PageResponse]를 중첩하지 않고 펼쳐 담는다. 중첩하면 이 화면만 `page.contents`로 읽고 다른
 * 목록은 `contents`로 읽게 되어, 같은 페이지 개념이 응답마다 다른 깊이에 놓인다.
 */
@Schema(description = "사진 목록 한 페이지")
data class PhotoPageResponse(
    val page: Int,
    val size: Int,
    val totalCount: Long,
    val hasNext: Boolean,
    val contents: List<PhotoResponse>,

    @field:Schema(
        description = "viewUrl이 살아 있는 시간(초). 프론트는 이 시간이 지나기 전에 목록을 다시 불러야 한다.",
    )
    val viewUrlTtlSeconds: Long,
) {

    companion object {
        fun of(page: PageResponse<PhotoResponse>, viewUrlTtlSeconds: Long) = PhotoPageResponse(
            page = page.page,
            size = page.size,
            totalCount = page.totalCount,
            hasNext = page.hasNext,
            contents = page.contents,
            viewUrlTtlSeconds = viewUrlTtlSeconds,
        )
    }
}
