package com.soma.wes.recommendation.service

import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.support.FakeStructuredLlmClient
import com.soma.wes.recommendation.dto.LlmPartDto
import com.soma.wes.recommendation.exception.RecommendationErrorCode
import com.soma.wes.recommendation.exception.RecommendationException
import com.soma.wes.recommendation.fixture.RecommendationFixture
import com.soma.wes.recommendation.repository.AiPairVerdictRepository
import com.soma.wes.selection.fixture.SelectionFixture
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

@IntegrationTest
class PairVerdictJudgeTest @Autowired constructor(
    private val judge: PairVerdictJudge,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val recommendationFixture: RecommendationFixture,
    private val selectionFixture: SelectionFixture,
    private val aiPairVerdictRepository: AiPairVerdictRepository,
    private val llm: FakeStructuredLlmClient,
) {

    private var galleryId = 0L
    private var selectionId = 0L
    private var photoA = 0L
    private var photoB = 0L

    @BeforeEach
    fun setUpBaseData() {
        llm.reset()
        val gallery = galleryFixture.멤버와_열린_갤러리()
        galleryId = gallery.galleryId
        selectionId = selectionFixture.셀렉(galleryId)
        val photos = photoFixture.임베딩된_사진(galleryId, 2)
        photoA = photos[0]
        photoB = photos[1]
        photos.forEach { recommendationFixture.미리보기(it) }
    }

    @Nested
    @DisplayName("LLM이 켜져 있을 때")
    inner class WithLlm {

        @Test
        fun `LLM 판정을 저장하고 순서를 뒤집은 재요청은 캐시로 돌려준다`() {
            // given
            recommendationFixture.분석_결과(photoA, clusterId = 1, clusterRank = 0)
            recommendationFixture.분석_결과(photoB, clusterId = 2, clusterRank = 0)
            llm.respondWith("""{"chosen": "b", "confidence": "clear", "reason": "시선이 살아 있어요"}""")

            // when
            val first = judge.judge(selectionId, galleryId, photoA, photoB)
            val second = judge.judge(selectionId, galleryId, photoB, photoA)

            // then
            assertSoftly { softly ->
                softly.assertThat(first.chosenPhotoId).isEqualTo(photoB)
                softly.assertThat(first.source).isEqualTo("llm")
                softly.assertThat(first.cached).isFalse()
                softly.assertThat(second.cached).isTrue()
                softly.assertThat(second.chosenPhotoId).isEqualTo(photoB)
                softly.assertThat(llm.calls).isEqualTo(1)
                softly.assertThat(aiPairVerdictRepository.count()).isEqualTo(1L)
            }
        }

        @Test
        fun `사진 두 장과 재료 문장을 라벨 순서대로 보낸다`() {
            // given
            recommendationFixture.분석_결과(photoA, technicalPct = 90.0)
            recommendationFixture.분석_결과(photoB, technicalPct = 60.0)
            llm.respondWith("""{"chosen": "a", "confidence": "clear", "reason": "화질이 좋아요"}""")

            // when
            judge.judge(selectionId, galleryId, photoA, photoB)

            // then
            val parts = llm.requests.single().parts
            val images = parts.filterIsInstance<LlmPartDto.Image>().map { String(it.jpeg) }
            val material = (parts.last() as LlmPartDto.Text).text
            assertSoftly { softly ->
                softly.assertThat(images).containsExactly("previews/$photoA.jpg", "previews/$photoB.jpg")
                softly.assertThat(material).contains("기술(화질) 백분위: 사진 a 가 30포인트 높다")
                softly.assertThat(llm.requests.single().timeout).isNotNull()
                softly.assertThat(llm.requests.single().maxRetries).isZero()
            }
        }

        @Test
        fun `계약을 벗어난 응답이나 예외는 템플릿 판정으로 흡수한다`() {
            // given
            recommendationFixture.분석_결과(photoA, sharpness = 200.0)
            recommendationFixture.분석_결과(photoB, sharpness = 100.0)
            llm.respondWith("""{"chosen": "c", "confidence": "clear", "reason": "x"}""")

            // when
            val outOfContract = judge.judge(selectionId, galleryId, photoA, photoB)
            aiPairVerdictRepository.deleteAll()
            llm.failure = RecommendationException(RecommendationErrorCode.LLM_CALL_FAILED)
            val failed = judge.judge(selectionId, galleryId, photoA, photoB)

            // then — 초점 1.25배 우선 규칙으로 a
            assertSoftly { softly ->
                softly.assertThat(outOfContract.source).isEqualTo("template")
                softly.assertThat(outOfContract.chosenPhotoId).isEqualTo(photoA)
                softly.assertThat(failed.source).isEqualTo("template")
                softly.assertThat(failed.chosenPhotoId).isEqualTo(photoA)
                softly.assertThat(failed.reason).isNotBlank()
            }
        }

        @Test
        fun `프롬프트 세대가 바뀐 저장 판정은 캐시가 아니라 다시 판정해 덮는다`() {
            // given
            recommendationFixture.분석_결과(photoA)
            recommendationFixture.분석_결과(photoB)
            llm.respondWith("""{"chosen": "a", "confidence": "slight", "reason": "비슷해요"}""")
            judge.judge(selectionId, galleryId, photoA, photoB)
            val stored = aiPairVerdictRepository.findByPair(selectionId, photoA, photoB)!!
            stored.replaceWith(stored.chosenPhotoId, stored.confidence, stored.reason, stored.facts, "old-model+compare-p1", stored.source)
            aiPairVerdictRepository.saveAndFlush(stored)
            llm.respondWith("""{"chosen": "b", "confidence": "clear", "reason": "다시 봤어요"}""")

            // when
            val again = judge.judge(selectionId, galleryId, photoA, photoB)

            // then
            assertSoftly { softly ->
                softly.assertThat(again.cached).isFalse()
                softly.assertThat(again.chosenPhotoId).isEqualTo(photoB)
                softly.assertThat(llm.calls).isEqualTo(2)
                softly.assertThat(aiPairVerdictRepository.count()).isEqualTo(1L)
            }
        }
    }

    @Nested
    @DisplayName("LLM이 꺼져 있을 때")
    inner class WithoutLlm {

        @Test
        fun `템플릿 판정을 slight로 저장하고 분석되지 않은 사진은 거절한다`() {
            // given
            llm.isEnabled = false
            recommendationFixture.분석_결과(photoA)
            recommendationFixture.분석_결과(photoB)
            val ghost = photoFixture.임베딩된_사진(galleryId, 1).single()

            // when
            val verdict = judge.judge(selectionId, galleryId, photoA, photoB)

            // then
            assertSoftly { softly ->
                softly.assertThat(verdict.source).isEqualTo("template")
                softly.assertThat(verdict.confidence).isEqualTo("slight")
                softly.assertThat(verdict.chosenPhotoId).isEqualTo(photoA)
                softly.assertThat(llm.calls).isZero()
            }
            assertThatThrownBy { judge.judge(selectionId, galleryId, photoA, ghost) }
                .isInstanceOf(RecommendationException::class.java)
                .extracting("errorCode")
                .isEqualTo(RecommendationErrorCode.COMPARE_NOT_ANALYZED)
        }
    }
}
