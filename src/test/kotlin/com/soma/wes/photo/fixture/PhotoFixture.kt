package com.soma.wes.photo.fixture

import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.domain.PhotoAnalysis
import com.soma.wes.photo.repository.PhotoAnalysisRepository
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.support.TestSequence
import org.springframework.stereotype.Component

@Component
class PhotoFixture(
    private val photoRepository: PhotoRepository,
    private val photoAnalysisRepository: PhotoAnalysisRepository,
) {

    fun 업로드된_사진(galleryId: Long, count: Int): List<Long> = 사진(galleryId, count, uploaded = true)

    /** 업로드 URL만 발급된 PENDING 상태. 완료 통보가 오지 않은 사진이다. */
    fun 대기중_사진(galleryId: Long, count: Int): List<Long> = 사진(galleryId, count, uploaded = false)

    /**
     * 벡터까지 적재된 사진. 벡터 값은 의미가 없다(전부 같은 방향) — 클러스터 결과를
     * 보는 테스트는 [벡터_적재]로 원하는 벡터를 직접 넣는다.
     */
    fun 임베딩된_사진(galleryId: Long, count: Int): List<Long> =
        업로드된_사진(galleryId, count).onEach { photoId ->
            벡터_적재(photoId, FloatArray(PhotoAnalysis.EMBEDDING_DIMENSION).also { it[0] = 1f })
        }

    /** 사진에 벡터를 싣는다. 임베더 Lambda가 분석 행을 UPSERT 하는 것을 흉내 낸다 — 사진 상태는 건드리지 않는다. */
    fun 벡터_적재(photoId: Long, vector: FloatArray): PhotoAnalysis =
        photoAnalysisRepository.saveAndFlush(
            PhotoAnalysis.embeddedBy(photoId = photoId, vector = vector, model = EMBEDDING_MODEL),
        )

    private fun 사진(galleryId: Long, count: Int, uploaded: Boolean): List<Long> =
        (1..count).map { index ->
            val photo = Photo(
                galleryId = galleryId,
                storageKey = "galleries/$galleryId/${TestSequence.next()}.jpg",
                originalFileName = "$index.jpg",
                contentType = "image/jpeg",
                displayOrder = index,
            )
            if (uploaded) {
                photo.markUploaded()
            }
            photoRepository.save(photo).requiredId
        }

    companion object {
        const val EMBEDDING_MODEL = "facebook/dinov3-vitb16-pretrain-lvd1689m"
    }
}
