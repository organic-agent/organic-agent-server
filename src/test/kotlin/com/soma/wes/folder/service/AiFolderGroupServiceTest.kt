package com.soma.wes.folder.service

import com.soma.wes.folder.domain.FolderCategory
import com.soma.wes.folder.domain.FolderOrigin
import com.soma.wes.folder.exception.FolderErrorCode
import com.soma.wes.folder.exception.FolderException
import com.soma.wes.folder.repository.PhotoFolderGroupRepository
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.gallery.fixture.OpenGallery
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.recommendation.fixture.RecommendationFixture
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * AI 폴더 세트 생성을 서비스 경계에서 확인한다. 배정·분석 행은 AI 배치가 쓰는 것을 픽스처가 흉내 낸다.
 */
@IntegrationTest
@DisplayName("AI 폴더 세트를 만들 때")
class AiFolderGroupServiceTest @Autowired constructor(
    private val aiFolderGroupService: AiFolderGroupService,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val recommendationFixture: RecommendationFixture,
    private val photoRepository: PhotoRepository,
    private val photoFolderGroupRepository: PhotoFolderGroupRepository,
) {

    private lateinit var fixture: OpenGallery

    @BeforeEach
    fun setUpBaseData() {
        fixture = galleryFixture.멤버와_열린_갤러리()
    }


    @Test
    fun `최신 배정으로 부모-컨셉 세트를 만든다`() {
        // given
        val photoIds = photoFixture.임베딩된_사진(fixture.galleryId, count = 3)
        recommendationFixture.분석_결과(photoIds[0], embedGroupId = 1, subjects = "bride")
        recommendationFixture.분석_결과(photoIds[1], embedGroupId = 1, subjects = "bride")
        recommendationFixture.분석_결과(photoIds[2], embedGroupId = 2, subjects = "couple")
        val jobId = recommendationFixture.분석_잡(fixture.galleryId)
        recommendationFixture.컨셉_배정(jobId, fixture.galleryId, embedGroupId = 1, parentName = "실내 스튜디오", conceptName = "스튜디오 소파")
        recommendationFixture.컨셉_배정(jobId, fixture.galleryId, embedGroupId = 2, parentName = "야외 자연", conceptName = "해변", needsReview = true)

        // when
        val result = aiFolderGroupService.createFromAnalysis(fixture.galleryId, fixture.photographer.id!!)

        // then
        assertSoftly { softly ->
            softly.assertThat(result).hasSize(2)
            softly.assertThat(result.map { it.name }).containsExactly("실내 스튜디오", "야외 자연")
            softly.assertThat(result).allSatisfy { group ->
                assertThat(group.origin).isEqualTo(FolderOrigin.AI)
                assertThat(group.analysisJobId).isEqualTo(jobId)
            }
            val sofa = result[0].folders.single()
            softly.assertThat(sofa.name).isEqualTo("스튜디오 소파")
            softly.assertThat(sofa.category).isEqualTo(FolderCategory.BRIDE)
            softly.assertThat(sofa.needsReview).isFalse()
            softly.assertThat(sofa.photoCount).isEqualTo(2L)
            val beach = result[1].folders.single()
            softly.assertThat(beach.needsReview).isTrue()
            softly.assertThat(beach.photoCount).isEqualTo(1L)
        }
    }

    @Test
    fun `배정이 없으면 거절한다`() {
        // 분석(naming)이 돈 적 없다 — AI 분석 요청이 먼저다.
        // given
        photoFixture.임베딩된_사진(fixture.galleryId, count = 1)

        // when & then
        assertThatThrownBy {
            aiFolderGroupService.createFromAnalysis(fixture.galleryId, fixture.photographer.id!!)
        }
            .isInstanceOf(FolderException::class.java)
            .extracting("errorCode")
            .isEqualTo(FolderErrorCode.ANALYSIS_NOT_COMPLETE)
    }

    @Test
    fun `분석 행이 있는 사진이 없으면 거절한다`() {
        // given — 배정만 있고(이전 사진은 지워짐) 분석 행이 있는 사진이 없다.
        val jobId = recommendationFixture.분석_잡(fixture.galleryId)
        recommendationFixture.컨셉_배정(jobId, fixture.galleryId, embedGroupId = 1, parentName = "야외 자연", conceptName = "해변")
        photoFixture.업로드된_사진(fixture.galleryId, count = 1)

        // when & then
        assertThatThrownBy {
            aiFolderGroupService.createFromAnalysis(fixture.galleryId, fixture.photographer.id!!)
        }
            .isInstanceOf(FolderException::class.java)
            .extracting("errorCode")
            .isEqualTo(FolderErrorCode.NO_PHOTOS_TO_ORGANIZE)
    }

    @Test
    fun `다시 부르면 기존 세트를 두고 새 세트를 더 만든다`() {
        // 사용자가 편집한 폴더를 서버가 지우지 않는다 — 옛 세트는 사용자가 지운다.
        // given
        val photoIds = photoFixture.임베딩된_사진(fixture.galleryId, count = 1)
        recommendationFixture.분석_결과(photoIds[0], embedGroupId = 1)
        val jobId = recommendationFixture.분석_잡(fixture.galleryId)
        recommendationFixture.컨셉_배정(jobId, fixture.galleryId, embedGroupId = 1, parentName = "야외 자연", conceptName = "해변")
        val first = aiFolderGroupService.createFromAnalysis(fixture.galleryId, fixture.photographer.id!!)

        // when
        val second = aiFolderGroupService.createFromAnalysis(fixture.galleryId, fixture.photographer.id!!)

        // then
        assertSoftly { softly ->
            softly.assertThat(second.map { it.groupId }).doesNotContainAnyElementsOf(first.map { it.groupId })
            softly.assertThat(photoFolderGroupRepository.findAllByGalleryIdOrderByCreatedAtDesc(fixture.galleryId))
                .hasSize(2)
        }
    }

    @Test
    fun `휴지통에 든 사진은 세트에 넣지 않는다`() {
        // given
        val photoIds = photoFixture.임베딩된_사진(fixture.galleryId, count = 2)
        recommendationFixture.분석_결과(photoIds[0], embedGroupId = 1)
        recommendationFixture.분석_결과(photoIds[1], embedGroupId = 1)
        val jobId = recommendationFixture.분석_잡(fixture.galleryId)
        recommendationFixture.컨셉_배정(jobId, fixture.galleryId, embedGroupId = 1, parentName = "야외 자연", conceptName = "해변")
        photoRepository.findById(photoIds[1]).orElseThrow().also {
            it.moveToTrash(java.time.ZonedDateTime.now())
            photoRepository.saveAndFlush(it)
        }

        // when
        val result = aiFolderGroupService.createFromAnalysis(fixture.galleryId, fixture.photographer.id!!)

        // then
        assertThat(result.single().folders.single().photoCount).isEqualTo(1L)
    }

    @Test
    fun `담당 작가가 아니면 거절한다`() {
        // 부부는 만들어진 폴더를 편집만 한다 — 세트 생성은 작가의 일이다.
        // given
        val photoIds = photoFixture.임베딩된_사진(fixture.galleryId, count = 1)
        recommendationFixture.분석_결과(photoIds[0], embedGroupId = 1)
        val jobId = recommendationFixture.분석_잡(fixture.galleryId)
        recommendationFixture.컨셉_배정(jobId, fixture.galleryId, embedGroupId = 1, parentName = "야외 자연", conceptName = "해변")

        // when & then
        assertThatThrownBy {
            aiFolderGroupService.createFromAnalysis(fixture.galleryId, fixture.member.id!!)
        }
            .isInstanceOf(GalleryException::class.java)
            .extracting("errorCode")
            .isEqualTo(GalleryErrorCode.GALLERY_ACCESS_DENIED)
    }
}
