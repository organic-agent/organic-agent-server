package com.soma.wes.analysis.support

import com.soma.wes.analysis.dto.ConceptAssignmentDto
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.recommendation.fixture.RecommendationFixture
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

@IntegrationTest
@DisplayName("최신 컨셉 배정을 읽을 때")
class ConceptAssignmentLoaderTest @Autowired constructor(
    private val loader: ConceptAssignmentLoader,
    private val galleryFixture: GalleryFixture,
    private val recommendationFixture: RecommendationFixture,
) {

    /** 배정은 잡마다 쌓이고 임베딩 그룹 번호는 잡마다 새로 매겨진다 — 같은 번호의 옛 이름표가 섞이면 안 된다. */
    @Test
    fun `가장 최근 잡의 배정만 돌려준다`() {
        // given
        val galleryId = galleryFixture.멤버와_열린_갤러리().galleryId
        val oldJobId = recommendationFixture.분석_잡(galleryId)
        recommendationFixture.컨셉_배정(oldJobId, galleryId, embedGroupId = 1, conceptName = "야외 자연", detailName = "해변")
        recommendationFixture.컨셉_배정(oldJobId, galleryId, embedGroupId = 2, conceptName = "야외 자연", detailName = "숲")
        val newJobId = recommendationFixture.분석_잡(galleryId)
        recommendationFixture.컨셉_배정(newJobId, galleryId, embedGroupId = 1, conceptName = "야외 정원·건물", detailName = "정원")

        // when
        val latest = loader.loadLatest(galleryId)

        // then
        assertSoftly { softly ->
            softly.assertThat(latest?.analysisJobId).isEqualTo(newJobId)
            softly.assertThat(latest?.assignments).containsExactly(
                ConceptAssignmentDto(embedGroupId = 1, conceptName = "야외 정원·건물", detailName = "정원"),
            )
        }
    }

    @Test
    fun `배정이 없으면 null이다`() {
        // given
        val galleryId = galleryFixture.멤버와_열린_갤러리().galleryId
        recommendationFixture.분석_잡(galleryId)

        // when
        val latest = loader.loadLatest(galleryId)

        // then
        assertThat(latest).isNull()
    }
}
