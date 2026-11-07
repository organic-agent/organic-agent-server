package com.soma.wes.recommendation.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PhotoSubjectUnitTest {

    @Test
    fun `AI가 쓰는 소문자 값을 같은 피사체로 읽는다`() {
        // when
        val subjects = listOf("bride", "groom", "couple", "group", "unknown").map { PhotoSubject.from(it) }

        // then
        assertThat(subjects).containsExactly(
            PhotoSubject.BRIDE,
            PhotoSubject.GROOM,
            PhotoSubject.COUPLE,
            PhotoSubject.GROUP,
            PhotoSubject.UNKNOWN,
        )
    }

    @Test
    fun `판정이 없거나 모르는 값이면 UNKNOWN이다`() {
        // when
        val subjects = listOf(null, "pet", "GROUP").map { PhotoSubject.from(it) }

        // then
        assertThat(subjects).containsOnly(PhotoSubject.UNKNOWN)
    }
}
