package com.soma.wes.photo.fixture

import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.repository.PhotoRepository
import com.soma.wes.support.TestSequence
import org.springframework.stereotype.Component

@Component
class PhotoFixture(
    private val photoRepository: PhotoRepository,
) {

    fun 업로드된_사진(galleryId: Long, count: Int): List<Long> = 사진(galleryId, count, uploaded = true)

    /** 업로드 URL만 발급된 PENDING 상태. 완료 통보가 오지 않은 사진이다. */
    fun 대기중_사진(galleryId: Long, count: Int): List<Long> = 사진(galleryId, count, uploaded = false)

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
}
