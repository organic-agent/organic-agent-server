package com.soma.wes.folder.support

import com.soma.wes.folder.domain.FolderSource
import com.soma.wes.folder.repository.DetailFolderAssignmentRepository
import com.soma.wes.folder.service.FolderService
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.recommendation.fixture.RecommendationFixture
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

// [REFACTOR-RENAME 2026-09-27] AiFolderService → AiFolderMaterializeService (클래스 이름만 변경, 동작 동일). 테스트 파일도 AiFolderServiceTest에서 이름 변경
// [REFACTOR-SUPPORT 2026-09-27] folder/service/AiFolderMaterializeServiceTest → folder/support/AiFolderMaterializerTest (프로덕션 패키지 미러링)
@IntegrationTest
@DisplayName("AI 폴더 세트를 실체화할 때")
class AiFolderMaterializerTest @Autowired constructor(
    private val materializer: AiFolderMaterializer,
    private val folderService: FolderService,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val recommendationFixture: RecommendationFixture,
    private val assignmentRepository: DetailFolderAssignmentRepository,
) {

    /**
     * 배경: 해변 3장(그룹 1) + 정원 2장(그룹 2) + 분석 행은 있지만 그룹이 없는 1장(→ "기타").
     * 새 세트를 만드는 응답은 DB를 다시 읽지 않고 메모리에서 조립하므로, 같은 세트를 두 번째 요청했을 때
     * DB에서 읽어 만든 응답과 같아야 한다. 작가 버튼([FolderService.createFromAnalysis])도 같은 세트를 돌려준다.
     */
    @Test
    fun `새로 만든 응답은 DB에서 다시 읽은 응답과 같고 배정 행이 배치로 적재된다`() {
        // given
        val fixture = galleryFixture.멤버와_열린_갤러리()
        val beach = analyzed(fixture.galleryId, count = 3, embedGroupId = 1)
        val garden = analyzed(fixture.galleryId, count = 2, embedGroupId = 2)
        val ungrouped = photoFixture.임베딩된_사진(fixture.galleryId, 1).single()
        val jobId = recommendationFixture.분석_잡(fixture.galleryId)
        recommendationFixture.컨셉_배정(jobId, fixture.galleryId, embedGroupId = 1, conceptName = "야외 자연", detailName = "해변")
        recommendationFixture.컨셉_배정(jobId, fixture.galleryId, embedGroupId = 2, conceptName = "야외 정원·건물", detailName = "정원")

        // when
        val created = materializer.materialize(fixture.galleryId)
        val reread = materializer.materialize(fixture.galleryId)
        val fromButton = folderService.createFromAnalysis(fixture.galleryId, fixture.photographer.requiredId)

        // then
        val assignments = assignmentRepository.findAllPhotoIdsByGalleryId(fixture.galleryId)
        assertSoftly { softly ->
            softly.assertThat(reread).isEqualTo(created)
            softly.assertThat(fromButton).isEqualTo(created)
            softly.assertThat(created.map { it.name }).containsExactly("야외 자연", "야외 정원·건물", "기타")
            softly.assertThat(created.flatMap { c -> c.details.map { it.photoIds } })
                .containsExactly(beach, garden, listOf(ungrouped))
            softly.assertThat(assignments).containsExactlyInAnyOrderElementsOf(beach + garden + ungrouped)
            softly.assertThat(assignmentRepository.findAllByPhotoIdIn(beach).map { it.assignedSource })
                .containsOnly(FolderSource.AI)
        }
        assertThat(created.flatMap { it.details }.map { it.galleryId }).containsOnly(fixture.galleryId)
    }

    private fun analyzed(galleryId: Long, count: Int, embedGroupId: Int): List<Long> =
        photoFixture.임베딩된_사진(galleryId, count).onEach { photoId ->
            recommendationFixture.분석_결과(photoId = photoId, embedGroupId = embedGroupId, burstId = embedGroupId)
        }
}
