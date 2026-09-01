package com.soma.wes.embedding.service

import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.support.GalleryAccessPolicy
import com.soma.wes.photo.domain.PhotoStatus
import com.soma.wes.photo.repository.PhotoRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class EmbeddingServiceTest {

    private val galleryAccessPolicy = mock<GalleryAccessPolicy>()
    private val photoRepository = mock<PhotoRepository>()
    private val embeddingInvoker = mock<EmbeddingInvoker>()
    private val service = EmbeddingService(galleryAccessPolicy, photoRepository, embeddingInvoker)

    @Test
    fun `일반 실행은 PENDING을 제외한 미완료 임베딩 수를 반환한다`() {
        // given
        stubAvailableInvoker()
        whenever(
            photoRepository.countByGalleryIdAndStatusNotAndNotEmbedded(GALLERY_ID, PhotoStatus.PENDING),
        ).thenReturn(2)

        // when
        val response = service.run(GALLERY_ID, USER_ID, force = false)

        // then
        assertThat(response.targets).isEqualTo(2)
        verify(photoRepository)
            .countByGalleryIdAndStatusNotAndNotEmbedded(GALLERY_ID, PhotoStatus.PENDING)
        verify(embeddingInvoker).invoke(GALLERY_ID, false)
    }

    @Test
    fun `force 실행도 PENDING을 제외한 업로드 완료 사진 수를 반환한다`() {
        // given
        stubAvailableInvoker()
        whenever(photoRepository.countByGalleryIdAndStatusNot(GALLERY_ID, PhotoStatus.PENDING)).thenReturn(3)

        // when
        val response = service.run(GALLERY_ID, USER_ID, force = true)

        // then
        assertThat(response.targets).isEqualTo(3)
        verify(photoRepository).countByGalleryIdAndStatusNot(GALLERY_ID, PhotoStatus.PENDING)
        verify(embeddingInvoker).invoke(GALLERY_ID, true)
    }

    private fun stubAvailableInvoker() {
        whenever(galleryAccessPolicy.requirePhotographer(GALLERY_ID, USER_ID)).thenReturn(mock<Gallery>())
        whenever(embeddingInvoker.isAvailable).thenReturn(true)
    }

    private companion object {
        const val GALLERY_ID = 10L
        const val USER_ID = 20L
    }
}
