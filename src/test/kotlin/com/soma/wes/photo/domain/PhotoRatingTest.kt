package com.soma.wes.photo.domain

import com.soma.wes.photo.exception.PhotoErrorCode
import com.soma.wes.photo.exception.PhotoException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 별점이 스스로 지키는 규칙 — 범위(1~5)와 덮어쓰기.
 *
 * 컨트롤러의 `@Valid`에 기대지 않고 도메인에서 막는지를 본다.
 */
class PhotoRatingTest {

    @Test
    fun `0점과 6점은 받지 않는다`() {
        listOf(0, 6, -1).forEach { score ->
            val exception = assertFailsWith<PhotoException> { PhotoRating.of(photoId = 1L, score = score, ratedBy = 1L) }

            assertEquals(PhotoErrorCode.INVALID_SCORE, exception.errorCode, "score=$score")
        }
    }

    @Test
    fun `경계값 1점과 5점은 받는다`() {
        assertEquals(1, PhotoRating.of(photoId = 1L, score = 1, ratedBy = 1L).score)
        assertEquals(5, PhotoRating.of(photoId = 1L, score = 5, ratedBy = 1L).score)
    }

    @Test
    fun `다시 매기면 점수와 매긴 사람이 함께 덮어써진다`() {
        // 사진당 한 행이라 신부가 매긴 뒤 신랑이 매기면 그대로 바뀐다. ratedBy는 마지막 사람이다.
        val rating = PhotoRating.of(photoId = 1L, score = 3, ratedBy = 10L)

        rating.rate(score = 5, ratedBy = 20L)

        assertEquals(5, rating.score)
        assertEquals(20L, rating.ratedBy)
    }

    @Test
    fun `덮어쓸 때도 범위를 본다`() {
        val rating = PhotoRating.of(photoId = 1L, score = 3, ratedBy = 10L)

        val exception = assertFailsWith<PhotoException> { rating.rate(score = 9, ratedBy = 10L) }

        assertEquals(PhotoErrorCode.INVALID_SCORE, exception.errorCode)
        // 거절된 요청이 기존 점수를 건드리지 않는다.
        assertEquals(3, rating.score)
    }
}
