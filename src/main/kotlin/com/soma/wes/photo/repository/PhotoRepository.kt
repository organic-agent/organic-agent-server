package com.soma.wes.photo.repository

import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.domain.PhotoStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface PhotoRepository : JpaRepository<Photo, Long> {

    fun findAllByGalleryIdOrderByDisplayOrderAsc(galleryId: Long): List<Photo>

    fun findAllByGalleryId(galleryId: Long, pageable: Pageable): Page<Photo>

    fun findAllByGalleryIdAndStatus(galleryId: Long, status: PhotoStatus, pageable: Pageable): Page<Photo>

    fun findAllByGalleryIdAndIdIn(galleryId: Long, ids: Collection<Long>): List<Photo>

    fun countByGalleryId(galleryId: Long): Long

    fun countByGalleryIdAndStatus(galleryId: Long, status: PhotoStatus): Long

    /**
     * 임베딩 실행 대상 수.
     *
     * `status`가 아니라 `embedding` 컬럼을 보는 이유는 Lambda가 대상을 고르는 기준과 같아야
     * 하기 때문이다. 상태 값이 어긋나 있어도 이 수는 진실을 말한다.
     */
    fun countByGalleryIdAndEmbeddingIsNull(galleryId: Long): Long
}
