package com.soma.wes.recommendation.support

import com.soma.wes.recommendation.dto.ComparablePhotoDto
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TemplateVerdictRuleUnitTest {

    @Test
    fun `초점 1_25배 ▸ 기술 백분위 ▸ 미학 백분위 ▸ a 순으로 고른다`() {
        assertThat(TemplateVerdictRule.decide(photo("a", sharpness = 200.0), photo("b", sharpness = 100.0)).chosen)
            .isEqualTo(TemplateVerdictRule.Side.A)
        assertThat(TemplateVerdictRule.decide(photo("a", technical = 40.0), photo("b", technical = 60.0)).chosen)
            .isEqualTo(TemplateVerdictRule.Side.B)
        assertThat(TemplateVerdictRule.decide(photo("a", aesthetic = 70.0), photo("b", aesthetic = 60.0)).chosen)
            .isEqualTo(TemplateVerdictRule.Side.A)
        assertThat(TemplateVerdictRule.decide(photo("a"), photo("b")).chosen)
            .isEqualTo(TemplateVerdictRule.Side.A)
    }

    private fun photo(
        label: String,
        sharpness: Double? = null,
        technical: Double = 50.0,
        aesthetic: Double = 50.0,
    ) = ComparablePhotoDto(
        photoId = if (label == "a") 1 else 2,
        technicalPct = technical,
        aestheticPct = aesthetic,
        sharpness = sharpness,
        highlightClip = null,
        clusterId = -1,
        clusterRank = 0,
        subjects = "unknown",
    )
}
