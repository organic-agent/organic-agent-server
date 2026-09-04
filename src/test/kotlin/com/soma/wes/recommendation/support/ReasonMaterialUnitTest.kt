package com.soma.wes.recommendation.support

import com.soma.wes.recommendation.dto.RecommendablePhotoDto
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test

class ReasonMaterialUnitTest {

    @Test
    fun `원점수가 절대 하한 미만이면 백분위가 높아도 품질 재료를 만들지 않는다`() {
        // given — 백분위 95지만 ARNIQA 원점수 0.2 < 0.35
        val low = row(technicalScore = 0.2, aestheticScore = 6.0)
        val ok = row(technicalScore = 0.7, aestheticScore = 6.0)

        // when & then
        assertThat(ReasonMaterial.qualityMaterial(low)).isNull()
        assertThat(ReasonMaterial.qualityMaterial(ok)).isNotNull().containsKeys("aesthetic_top", "technical_top")
    }

    @Test
    fun `주 사유는 우선순위대로 고르고 folder는 폴더 1위일 때만 후보다`() {
        // given
        val topOfFolder = mapOf<String, Any?>("prior_z" to 0.2, "folder" to folder(rank = 1))
        val secondOfFolder = mapOf<String, Any?>("prior_z" to 0.2, "folder" to folder(rank = 2))
        val withQuality = topOfFolder + mapOf("quality" to mapOf("aesthetic_top" to 3, "descriptors" to listOf("초점이 또렷함")))

        // when
        val (primaryTop, factsTop) = ReasonMaterial.factsOf(topOfFolder)
        val (primarySecond, factsSecond) = ReasonMaterial.factsOf(secondOfFolder)
        val (primaryQuality, _) = ReasonMaterial.factsOf(withQuality)

        // then
        assertSoftly { softly ->
            softly.assertThat(primaryTop).isEqualTo("folder")
            softly.assertThat(factsTop).anyMatch { it.startsWith("폴더:") }
            softly.assertThat(primarySecond).isEqualTo("diversity")
            softly.assertThat(factsSecond).anyMatch { it.startsWith("다양성:") }
            softly.assertThat(primaryQuality).isEqualTo("quality")
            softly.assertThat(ReasonMaterial.template("quality", withQuality)).isEqualTo("전체 중 인상 상위 3%인 컷이에요, 초점이 또렷함")
            softly.assertThat(ReasonMaterial.template("folder", topOfFolder)).isEqualTo("\"야외 자연 › 해변\" 폴더 41장 중 1위인 컷이에요")
        }
    }

    @Test
    fun `형제가 밀린 이유는 선명도 ▸ 화질 ▸ 인상 순이다`() {
        // given
        val pick = row(sharpness = 200.0, technicalPct = 80.0, aestheticPct = 80.0)

        // when & then
        assertSoftly { softly ->
            softly.assertThat(ReasonMaterial.whyNot(pick, row(sharpness = 100.0))).isEqualTo("덜 선명함")
            softly.assertThat(ReasonMaterial.whyNot(pick, row(sharpness = 190.0, technicalPct = 60.0))).isEqualTo("화질이 떨어짐")
            softly.assertThat(ReasonMaterial.whyNot(pick, row(sharpness = 190.0, technicalPct = 80.0, aestheticPct = 60.0))).isEqualTo("인상이 약함")
            softly.assertThat(ReasonMaterial.whyNot(pick, row(sharpness = 190.0, technicalPct = 80.0, aestheticPct = 80.0))).isEqualTo("거의 같은 컷")
        }
    }

    private fun folder(rank: Int) = mapOf("parent" to "야외 자연", "name" to "해변", "size" to 41, "rank" to rank, "quota" to 3)

    private fun row(
        technicalPct: Double = 95.0,
        aestheticPct: Double = 95.0,
        sharpness: Double? = null,
        technicalScore: Double? = null,
        aestheticScore: Double? = null,
    ) = RecommendablePhotoDto(
        photoId = 1,
        technicalPct = technicalPct,
        aestheticPct = aestheticPct,
        subjects = "couple",
        clusterId = 1,
        clusterRank = 0,
        subScores = buildMap {
            sharpness?.let { put("sharpness", it) }
            technicalScore?.let { put("technical_score", it) }
            aestheticScore?.let { put("aesthetic_score", it) }
        },
    )
}
