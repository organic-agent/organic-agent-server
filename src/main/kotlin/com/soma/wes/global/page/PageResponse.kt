package com.soma.wes.global.page

import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.data.domain.Page

/**
 * 목록 한 페이지. 페이징이 있는 모든 응답이 이 모양을 쓴다.
 */
@Schema(description = "목록 한 페이지")
data class PageResponse<T>(

    @field:Schema(description = "0부터 시작한다. 요청의 page 파라미터와 같은 기준이다.")
    val page: Int,

    val size: Int,

    @field:Schema(description = "필터를 적용한 전체 개수. 현재 페이지의 개수가 아니다.")
    val totalCount: Long,

    val hasNext: Boolean,

    val contents: List<T>,
) {

    companion object {

        /**
         * 페이지 메타데이터는 [found]에서, 내용은 [contents]에서 가져온다.
         */
        fun <T> of(found: Page<*>, contents: List<T>): PageResponse<T> = PageResponse(
            page = found.number,
            size = found.size,
            totalCount = found.totalElements,
            hasNext = found.hasNext(),
            contents = contents,
        )
    }
}
