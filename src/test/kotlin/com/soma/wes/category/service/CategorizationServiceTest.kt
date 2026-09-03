package com.soma.wes.category.service

import com.soma.wes.category.domain.CategorizationMode
import com.soma.wes.category.domain.CategorizationStatus
import com.soma.wes.category.dto.request.MoveCategoryPhotosRequest
import com.soma.wes.category.repository.PhotoCategoryAssignmentRepository
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.recommendation.fixture.RecommendationFixture
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

@IntegrationTest
class CategorizationServiceTest @Autowired constructor(
    private val categorizationService: CategorizationService,
    private val categoryService: CategoryService,
    private val galleryFixture: GalleryFixture,
    private val photoFixture: PhotoFixture,
    private val recommendationFixture: RecommendationFixture,
    private val assignmentRepository: PhotoCategoryAssignmentRepository,
) {
    @Test
    fun `최신 AI 분석을 첫 실행은 전체에 물질화하고 재실행은 신규 사진만 처리한다`() {
        val fixture = galleryFixture.멤버와_열린_갤러리()
        val firstIds = analyzedPhotos(fixture.galleryId, count = 2, embedGroupId = 1)
        publishConceptAssignment(fixture.galleryId, embedGroupId = 1, conceptName = "본식")

        val initial = categorizationService.run(fixture.galleryId, fixture.photographer.requiredId)
        categoryService.movePhotos(
            fixture.galleryId,
            fixture.photographer.requiredId,
            MoveCategoryPhotosRequest(listOf(firstIds.first()), null),
        )
        val newPhotoId = analyzedPhotos(fixture.galleryId, count = 1, embedGroupId = 2).single()
        publishConceptAssignment(fixture.galleryId, embedGroupId = 2, conceptName = "연회")
        val incremental = categorizationService.run(fixture.galleryId, fixture.photographer.requiredId)

        assertThat(initial.mode).isEqualTo(CategorizationMode.INITIAL)
        assertThat(initial.status).isEqualTo(CategorizationStatus.SUCCEEDED)
        assertThat(initial.processedPhotoCount).isEqualTo(2)
        assertThat(incremental.mode).isEqualTo(CategorizationMode.INCREMENTAL)
        assertThat(incremental.processedPhotoCount).isEqualTo(1)
        assertThat(assignmentRepository.findById(firstIds.first())).isEmpty
        assertThat(assignmentRepository.findById(newPhotoId)).isPresent
    }

    private fun analyzedPhotos(galleryId: Long, count: Int, embedGroupId: Int): List<Long> {
        return photoFixture.임베딩된_사진(galleryId, count).onEach { photoId ->
            recommendationFixture.분석_결과(
                photoId = photoId,
                embedGroupId = embedGroupId,
                clusterId = embedGroupId,
            )
        }
    }

    private fun publishConceptAssignment(galleryId: Long, embedGroupId: Int, conceptName: String) {
        val jobId = recommendationFixture.분석_잡(galleryId)
        recommendationFixture.컨셉_배정(
            jobId = jobId,
            galleryId = galleryId,
            embedGroupId = embedGroupId,
            parentName = "웨딩",
            conceptName = conceptName,
        )
    }
}
