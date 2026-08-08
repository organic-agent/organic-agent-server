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

    /**
     * 사진 한 장을 갤러리와 함께 찾는다.
     *
     * `findById`로 찾고 나서 갤러리를 비교하지 않는다. 인가는 경로의 `galleryId`로 이미 끝났으므로,
     * 조회까지 그 갤러리로 좁히지 않으면 자기 갤러리 하나로 남의 사진 상세를 열 수 있다.
     * 없을 때와 남의 것일 때가 여기서 같은 결과(null)가 되는 것도 그래서 맞다.
     */
    fun findByIdAndGalleryId(id: Long, galleryId: Long): Photo?

    /**
     * 클러스터링 대상.
     *
     * `status`가 아니라 `embedding` 컬럼을 본다 — 벡터가 있어야 거리를 잴 수 있고, 상태 값이
     * 어긋나 있어도 이 조건은 진실을 말한다. 정렬을 못박는 이유는 묶음 안의 첫 장이 대표로
     * 쓰이기 때문이다. `displayOrder`만으로는 같은 배치에 발급된 사진들의 순서가 흔들린다.
     */
    fun findAllByGalleryIdAndEmbeddingIsNotNullOrderByDisplayOrderAscIdAsc(galleryId: Long): List<Photo>

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
