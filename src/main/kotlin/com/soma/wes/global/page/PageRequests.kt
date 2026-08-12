package com.soma.wes.global.page

import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort

/**
 * 목록 조회의 페이지 요청을 만든다. 페이징이 있는 모든 도메인이 여기를 지난다.
 */
object PageRequests {

    const val MAX_SIZE = 200

    /** `size`가 1보다 작을 때 대신 쓰는 크기. 화면별 기본값은 각 컨트롤러의 `defaultValue`가 정한다. */
    const val FALLBACK_SIZE = 200

    private const val MIN_PAGE = 0
    private const val MIN_SIZE = 1

    fun of(page: Int, size: Int, sort: Sort = Sort.unsorted()): PageRequest =
        PageRequest.of(
            page.coerceAtLeast(MIN_PAGE),
            if (size < MIN_SIZE) FALLBACK_SIZE else size.coerceAtMost(MAX_SIZE),
            sort,
        )
}
