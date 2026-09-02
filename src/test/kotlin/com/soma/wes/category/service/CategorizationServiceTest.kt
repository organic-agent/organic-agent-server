package com.soma.wes.category.service

import com.soma.wes.category.domain.CategorizationMode
import com.soma.wes.category.domain.CategorizationStatus
import com.soma.wes.category.dto.request.MoveCategoryPhotosRequest
import com.soma.wes.category.repository.PhotoCategoryAssignmentRepository
import com.soma.wes.gallery.fixture.GalleryFixture
import com.soma.wes.photo.domain.PhotoAnalysis
import com.soma.wes.photo.fixture.PhotoFixture
import com.soma.wes.photo.repository.PhotoAnalysisRepository
import com.soma.wes.photo.repository.PhotoRepository
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
    private val photoRepository: PhotoRepository,
    private val photoAnalysisRepository: PhotoAnalysisRepository,
    private val assignmentRepository: PhotoCategoryAssignmentRepository,
) {
    @Test
    fun `첫 실행은 전체를 처리하고 재실행은 신규 사진만 처리한다`() {
        val fixture = galleryFixture.멤버와_열린_갤러리()
        val firstIds = embeddedPhotos(fixture.galleryId, 2)

        val initial = categorizationService.run(fixture.galleryId, fixture.photographer.requiredId)
        categoryService.movePhotos(
            fixture.galleryId,
            fixture.photographer.requiredId,
            MoveCategoryPhotosRequest(listOf(firstIds.first()), null),
        )
        val newPhotoId = embeddedPhotos(fixture.galleryId, 1).single()
        val incremental = categorizationService.run(fixture.galleryId, fixture.photographer.requiredId)

        assertThat(initial.mode).isEqualTo(CategorizationMode.INITIAL)
        assertThat(initial.status).isEqualTo(CategorizationStatus.SUCCEEDED)
        assertThat(initial.processedPhotoCount).isEqualTo(2)
        assertThat(incremental.mode).isEqualTo(CategorizationMode.INCREMENTAL)
        assertThat(incremental.processedPhotoCount).isEqualTo(1)
        assertThat(assignmentRepository.findById(firstIds.first())).isEmpty
        assertThat(assignmentRepository.findById(newPhotoId)).isPresent
    }

    private fun embeddedPhotos(galleryId: Long, count: Int): List<Long> {
        val ids = photoFixture.업로드된_사진(galleryId, count)
        return ids.mapIndexed { index, id ->
            photoRepository.findById(id).orElseThrow().also { photo ->
                photoAnalysisRepository.save(
                    PhotoAnalysis.embeddedBy(
                        photoId = photo.requiredId,
                        vector = FloatArray(PhotoAnalysis.EMBEDDING_DIMENSION) { 0.01f * (index + 1) },
                        model = "test-model",
                    ),
                )
                photo.markEmbedded()
                photoRepository.save(photo)
            }.requiredId
        }
    }
}
