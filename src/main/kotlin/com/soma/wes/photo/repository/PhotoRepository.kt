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

    /** 사진 한 장을 갤러리와 함께 찾는다. */
    fun findByIdAndGalleryId(id: Long, galleryId: Long): Photo?

    /** 사진 행을 잠그고 찾는다. 별점을 매길 때 쓴다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockByIdAndGalleryId(id: Long, galleryId: Long): Photo?

    /** 최소 점수로 거른 목록. "4점 이상만 보기"가 지나는 곳이다. */
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

    /** 벡터가 적재된 사진. Mock 갤러리의 복제 원본이다. 벡터의 소유자는 분석 행이라 그 존재로 판정한다. */
    @Query(
        """
            SELECT p FROM Photo p
            WHERE p.galleryId = :galleryId
              AND EXISTS (SELECT 1 FROM PhotoAnalysis a WHERE a.photoId = p.id AND a.embedding IS NOT NULL)
            ORDER BY p.displayOrder ASC, p.id ASC
        """,
    )
    fun findAllEmbeddedByGalleryId(@Param("galleryId") galleryId: Long): List<Photo>

    /**
     * 다음 사진이 받을 노출 순서. 업로드 URL 발급이 쓴다.
     */
    @Query(
        value = "select coalesce(max(display_order) + 1, 0) from photos where gallery_id = :galleryId",
        nativeQuery = true,
    )
    fun nextDisplayOrder(@Param("galleryId") galleryId: Long): Int

    fun countByGalleryId(galleryId: Long): Long
}
