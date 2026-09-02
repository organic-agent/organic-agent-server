package com.soma.wes.recommendation.service

import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.domain.PhotoAnalysis
import com.soma.wes.photo.repository.PhotoAnalysisRepository
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.recommendation.dto.PairVerdictDto
import com.soma.wes.recommendation.dto.request.ComparePhotosRequest
import com.soma.wes.recommendation.exception.RecommendationErrorCode
import com.soma.wes.recommendation.exception.RecommendationException
import com.soma.wes.selection.domain.PhotoSelection
import com.soma.wes.selection.repository.PhotoSelectionRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class AiCompareServiceTest {

    private val galleryAccessPolicy = mock<GalleryAccessPolicy>()
    private val photoRepository = mock<PhotoRepository>()
    private val photoAnalysisRepository = mock<PhotoAnalysisRepository>()
    private val photoSelectionRepository = mock<PhotoSelectionRepository>()
    private val pairCompareInvoker = mock<PairCompareInvoker>()
    private val service = AiCompareService(
        galleryAccessPolicy,
        photoRepository,
        photoAnalysisRepository,
        photoSelectionRepository,
        pairCompareInvoker,
    )

    @Test
    fun `검증을 지나면 실행기의 판정을 그대로 돌려준다`() {
        // given
        stubComparablePhotos()
        whenever(photoSelectionRepository.findByGalleryId(GALLERY_ID)).thenReturn(selection())
        val verdict = PairVerdictDto(
            chosenPhotoId = PHOTO_A,
            confidence = "clear",
            reason = "이 컷이 초점이 더 선명해요.",
            source = "llm",
            cached = false,
        )
        whenever(pairCompareInvoker.compare(SELECTION_ID, PHOTO_A, PHOTO_B)).thenReturn(verdict)

        // when
        val response = service.compare(GALLERY_ID, USER_ID, request())

        // then
        assertSoftly { softly ->
            softly.assertThat(response.chosenPhotoId).isEqualTo(PHOTO_A)
            softly.assertThat(response.confidence).isEqualTo("clear")
            softly.assertThat(response.source).isEqualTo("llm")
            softly.assertThat(response.cached).isFalse()
        }
        verify(pairCompareInvoker).compare(SELECTION_ID, PHOTO_A, PHOTO_B)
    }

    @Test
    fun `셀렉 행이 없으면 만들어서 그 id로 판정한다`() {
        // 판정이 셀렉 단위로 저장·캐시되므로, 부부가 아직 아무것도 담지 않았어도 셀렉이 필요하다.
        // given
        stubComparablePhotos()
        whenever(photoSelectionRepository.findByGalleryId(GALLERY_ID)).thenReturn(null)
        whenever(photoSelectionRepository.save(any())).thenReturn(selection())
        whenever(pairCompareInvoker.compare(SELECTION_ID, PHOTO_A, PHOTO_B)).thenReturn(anyVerdict())

        // when
        val response = service.compare(GALLERY_ID, USER_ID, request())

        // then
        assertThat(response.chosenPhotoId).isEqualTo(PHOTO_A)
        verify(photoSelectionRepository).save(any())
    }

    @Test
    fun `같은 사진 두 장은 거절한다`() {
        // given
        stubAccess()

        // when & then
        assertThatThrownBy {
            service.compare(GALLERY_ID, USER_ID, ComparePhotosRequest(photoA = PHOTO_A, photoB = PHOTO_A))
        }
            .isInstanceOf(RecommendationException::class.java)
            .extracting("errorCode")
            .isEqualTo(RecommendationErrorCode.COMPARE_SAME_PHOTO)
        verify(pairCompareInvoker, never()).compare(any(), any(), any())
    }

    @Test
    fun `실행기가 설정되지 않았으면 호출 시점에 실패한다`() {
        // given
        stubAccess()
        whenever(pairCompareInvoker.isAvailable).thenReturn(false)

        // when & then
        assertThatThrownBy { service.compare(GALLERY_ID, USER_ID, request()) }
            .isInstanceOf(RecommendationException::class.java)
            .extracting("errorCode")
            .isEqualTo(RecommendationErrorCode.COMPARE_NOT_CONFIGURED)
    }

    @Test
    fun `이 갤러리에 없는 사진은 404다`() {
        // given — 갤러리 스코프 조회라 휴지통·남의 갤러리 사진은 조회에서 빠진다.
        stubAccess()
        whenever(pairCompareInvoker.isAvailable).thenReturn(true)
        whenever(photoRepository.findAllByGalleryIdAndIdIn(GALLERY_ID, listOf(PHOTO_A, PHOTO_B)))
            .thenReturn(listOf(mock<Photo>()))

        // when & then
        assertThatThrownBy { service.compare(GALLERY_ID, USER_ID, request()) }
            .isInstanceOf(RecommendationException::class.java)
            .extracting("errorCode")
            .isEqualTo(RecommendationErrorCode.COMPARE_PHOTO_NOT_FOUND)
    }

    @Test
    fun `분석이 끝나지 않은 사진은 거절한다`() {
        // 판정 재료가 분석 컬럼이다 — 5초를 기다리게 한 뒤 AI 쪽에서 죽는 것보다 여기서 거절한다.
        // given
        stubAccess()
        whenever(pairCompareInvoker.isAvailable).thenReturn(true)
        whenever(photoRepository.findAllByGalleryIdAndIdIn(GALLERY_ID, listOf(PHOTO_A, PHOTO_B)))
            .thenReturn(listOf(mock<Photo>(), mock<Photo>()))
        val embeddedOnly = mock<PhotoAnalysis> { on { isAnalyzed }.thenReturn(false) }
        val analyzed = mock<PhotoAnalysis> { on { isAnalyzed }.thenReturn(true) }
        whenever(photoAnalysisRepository.findAllByPhotoIdIn(listOf(PHOTO_A, PHOTO_B)))
            .thenReturn(listOf(embeddedOnly, analyzed))

        // when & then
        assertThatThrownBy { service.compare(GALLERY_ID, USER_ID, request()) }
            .isInstanceOf(RecommendationException::class.java)
            .extracting("errorCode")
            .isEqualTo(RecommendationErrorCode.COMPARE_NOT_ANALYZED)
        verify(pairCompareInvoker, never()).compare(any(), any(), any())
    }

    private fun request() = ComparePhotosRequest(photoA = PHOTO_A, photoB = PHOTO_B)

    private fun anyVerdict() = PairVerdictDto(
        chosenPhotoId = PHOTO_A,
        confidence = "slight",
        reason = "거의 같아요.",
        source = "template",
        cached = false,
    )

    /** requiredId는 저장된 행에만 있다 — DB 없는 단위 테스트라 실제 엔티티에 id를 심는다. */
    private fun selection(): PhotoSelection = PhotoSelection(galleryId = GALLERY_ID).also {
        PhotoSelection::class.java.getDeclaredField("id")
            .apply { isAccessible = true }
            .set(it, SELECTION_ID)
    }

    private fun stubAccess() {
        whenever(galleryAccessPolicy.requireCouple(GALLERY_ID, USER_ID)).thenReturn(mock<Gallery>())
    }

    private fun stubComparablePhotos() {
        stubAccess()
        whenever(pairCompareInvoker.isAvailable).thenReturn(true)
        whenever(photoRepository.findAllByGalleryIdAndIdIn(GALLERY_ID, listOf(PHOTO_A, PHOTO_B)))
            .thenReturn(listOf(mock<Photo>(), mock<Photo>()))
        val analyzed = mock<PhotoAnalysis> { on { isAnalyzed }.thenReturn(true) }
        whenever(photoAnalysisRepository.findAllByPhotoIdIn(listOf(PHOTO_A, PHOTO_B)))
            .thenReturn(listOf(analyzed, analyzed))
    }

    private companion object {
        const val GALLERY_ID = 10L
        const val USER_ID = 20L
        const val SELECTION_ID = 30L
        const val PHOTO_A = 101L
        const val PHOTO_B = 102L
    }
}
