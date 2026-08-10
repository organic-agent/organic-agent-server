package com.soma.wes.collab.support

import com.soma.wes.collab.exception.CollabErrorCode
import com.soma.wes.collab.exception.CollabException
import org.springframework.data.domain.PageRequest

/**
 * 협업 화면들이 공유하는 페이지 요청 검증.
 *
 * 사진 목록·하객 목록·댓글 목록이 각자 검증하면, 그중 한 곳만 음수 페이지를 막지 않아
 * `PageRequest.of`가 던지는 `IllegalArgumentException`이 500으로 나간다. 하객 경로는 로그인
 * 없이 열려 있어서 아무나 그 500을 만들어낼 수 있다.
 *
 * 정렬은 부르는 쪽이 정한다. 사진은 담은 순서, 댓글은 최신순이라 하나로 묶을 수 없다.
 */
object CollabPaging {

    fun of(page: Int, size: Int, maxSize: Int): PageRequest {
        if (page < 0) {
            throw CollabException(CollabErrorCode.INVALID_PAGE)
        }
        if (size !in 1..maxSize) {
            throw CollabException(CollabErrorCode.INVALID_PAGE_SIZE)
        }

        return PageRequest.of(page, size)
    }
}
