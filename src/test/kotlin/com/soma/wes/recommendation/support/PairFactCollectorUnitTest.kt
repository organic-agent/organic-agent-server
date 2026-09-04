package com.soma.wes.recommendation.support

import com.soma.wes.recommendation.dto.ComparablePhotoDto
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test

class PairFactCollectorUnitTest {

    @Test
    fun `연사·백분위 차·폴더·유형·담김을 문장과 저장용 구조로 만든다`() {
        // given
        val a = ComparablePhotoDto(1, technicalPct = 70.0, aestheticPct = 50.0, sharpness = null, highlightClip = null, clusterId = 3, clusterRank = 0, subjects = "couple")
        val b = ComparablePhotoDto(2, technicalPct = 50.0, aestheticPct = 50.0, sharpness = null, highlightClip = null, clusterId = 3, clusterRank = 1, subjects = "bride")

        // when
        val result = PairFactCollector.collect(a, b, "실내 › 세트0", "야외 › 해변", selected = setOf(2))

        // then
        val text = result.sentences.joinToString("\n")
        @Suppress("UNCHECKED_CAST")
        val sameBurst = result.facts["same_burst"] as Map<String, Any?>
        assertSoftly { softly ->
            softly.assertThat(text).contains("연사").contains("기술").contains("20포인트")
            softly.assertThat(text).contains("실내 › 세트0").contains("야외 › 해변")
            softly.assertThat(text).contains("유형").contains("이미 담은")
            softly.assertThat(sameBurst["best"]).isEqualTo("a")
            softly.assertThat(result.facts["already_selected"]).isEqualTo(listOf("b"))
            softly.assertThat(result.facts).doesNotContainKey("aesthetic_pct")
        }
    }

    @Test
    fun `차이가 없으면 사진에서 판단하라는 문장 하나만 남긴다`() {
        // given
        val a = ComparablePhotoDto(1, 50.0, 50.0, null, null, -1, 0, "unknown")
        val b = ComparablePhotoDto(2, 52.0, 51.0, null, null, -1, 0, "unknown")

        // when
        val result = PairFactCollector.collect(a, b, null, null, emptySet())

        // then
        assertThat(result.sentences).singleElement().asString().contains("측정된 차이 없음")
        assertThat(result.facts).isEmpty()
    }
}
