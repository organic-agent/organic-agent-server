package com.soma.wes.photo.repository

import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.domain.PhotoStatus
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

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
     * 사진 행을 잠그고 찾는다. 별점을 매길 때 쓴다.
     *
     * 별점은 사진당 한 행이라 "없으면 만들고 있으면 고친다"인데, 신랑과 신부가 같은 사진에
     * 동시에 별을 달면 둘 다 "아직 없다"를 읽고 각자 INSERT 한다. 유니크 제약이 막아주긴
     * 하지만 그때는 한쪽 요청이 통째로 500으로 실패한다. 별점 행은 아직 없을 수 있어
     * 잠글 대상이 못 되므로, 그 별점이 매달릴 사진 행을 잠근다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockByIdAndGalleryId(id: Long, galleryId: Long): Photo?

    /**
     * 최소 점수로 거른 목록. "4점 이상만 보기"가 지나는 곳이다.
     *
     * 별점을 조인이 아니라 EXISTS로 본다. 사진당 별점이 하나뿐이라 조인해도 행이 늘지는
     * 않지만, EXISTS는 페이지 크기만큼 찾으면 멈출 수 있고 무엇보다 "별점이 있는 사진만"이라는
     * 뜻이 질의에 그대로 드러난다.
     */
    @Query(
        value = """
            SELECT p FROM Photo p
            WHERE p.galleryId = :galleryId
              AND EXISTS (SELECT 1 FROM PhotoRating r WHERE r.photoId = p.id AND r.score >= :minScore)
        """,
        countQuery = """
            SELECT COUNT(p) FROM Photo p
            WHERE p.galleryId = :galleryId
              AND EXISTS (SELECT 1 FROM PhotoRating r WHERE r.photoId = p.id AND r.score >= :minScore)
        """,
    )
    fun findAllByGalleryIdAndScoreAtLeast(
        @Param("galleryId") galleryId: Long,
        @Param("minScore") minScore: Int,
        pageable: Pageable,
    ): Page<Photo>

    /** [findAllByGalleryIdAndScoreAtLeast]에 상태 조건을 더한 것. 두 조건은 함께 올 수 있다. */
    @Query(
        value = """
            SELECT p FROM Photo p
            WHERE p.galleryId = :galleryId
              AND p.status = :status
              AND EXISTS (SELECT 1 FROM PhotoRating r WHERE r.photoId = p.id AND r.score >= :minScore)
        """,
        countQuery = """
            SELECT COUNT(p) FROM Photo p
            WHERE p.galleryId = :galleryId
              AND p.status = :status
              AND EXISTS (SELECT 1 FROM PhotoRating r WHERE r.photoId = p.id AND r.score >= :minScore)
        """,
    )
    fun findAllByGalleryIdAndStatusAndScoreAtLeast(
        @Param("galleryId") galleryId: Long,
        @Param("status") status: PhotoStatus,
        @Param("minScore") minScore: Int,
        pageable: Pageable,
    ): Page<Photo>

    /**
     * 클러스터링 대상.
     *
     * `status`가 아니라 `embedding` 컬럼을 본다 — 벡터가 있어야 거리를 잴 수 있고, 상태 값이
     * 어긋나 있어도 이 조건은 진실을 말한다. 정렬을 못박는 이유는 묶음 안의 첫 장이 대표로
     * 쓰이기 때문이다. `displayOrder`만으로는 같은 배치에 발급된 사진들의 순서가 흔들린다.
     */
    fun findAllByGalleryIdAndEmbeddingIsNotNullOrderByDisplayOrderAscIdAsc(galleryId: Long): List<Photo>

    fun findAllByGalleryIdIn(galleryIds: Collection<Long>): List<Photo>

    @Query(value = "select * from photos where gallery_id in (:galleryIds) for update", nativeQuery = true)
    fun findAllByGalleryIdInForUpdate(@Param("galleryIds") galleryIds: Collection<Long>): List<Photo>

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
