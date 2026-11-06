package com.soma.wes.photo.repository

import com.soma.wes.photo.domain.Photo
import com.soma.wes.photo.domain.PhotoStatus
import java.time.Instant
import java.time.ZonedDateTime
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface PhotoRepository : JpaRepository<Photo, Long> {

    @Query("select p.id from Photo p where p.galleryId = :galleryId and p.excludedAt is null")
    fun findVisibleIdsByGalleryId(galleryId: Long): List<Long>

    fun findAllByGalleryIdAndExcludedAtIsNull(galleryId: Long, pageable: Pageable): Page<Photo>

    fun findAllByGalleryIdAndStatusAndExcludedAtIsNull(galleryId: Long, status: PhotoStatus, pageable: Pageable): Page<Photo>

    fun findAllByGalleryIdAndExcludedAtIsNotNull(galleryId: Long, pageable: Pageable): Page<Photo>

    fun findAllByGalleryIdAndExcludedAtIsNullOrderByDisplayOrderAsc(galleryId: Long): List<Photo>

    fun findAllByGalleryIdAndIdInAndExcludedAtIsNull(galleryId: Long, ids: Collection<Long>): List<Photo>

    /** 갤러리 잠금 뒤 id 순서로 사진도 잠가 공유 추가·실제 삭제와의 경쟁을 직렬화한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Photo p where p.galleryId = :galleryId and p.id in :ids order by p.id")
    fun findAllWithLockByGalleryIdAndIdIn(galleryId: Long, ids: Collection<Long>): List<Photo>

    /** 호출자가 갤러리·사진을 잠그고 전체 배치를 검증한 뒤 호출한다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update Photo p set p.excludedAt = :at, p.excludedBy = :userId, p.updatedAt = :at, p.version = p.version + 1
        where p.galleryId = :galleryId and p.id in :ids and p.excludedAt is null
    """)
    fun exclude(galleryId: Long, ids: Collection<Long>, userId: Long, at: ZonedDateTime): Int

    /** 호출자가 갤러리·사진을 잠그고 검증한다. 이미 복원된 행은 갱신하지 않아 변경 번호도 유지한다. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        update Photo p set p.excludedAt = null, p.excludedBy = null, p.updatedAt = :at, p.version = p.version + 1
        where p.galleryId = :galleryId and p.id in :ids and p.excludedAt is not null
    """)
    fun restoreExclusions(galleryId: Long, ids: Collection<Long>, at: ZonedDateTime): Int


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
              AND p.excludedAt IS NULL
              AND EXISTS (SELECT 1 FROM PhotoRating r WHERE r.photoId = p.id AND r.score >= :minScore)
        """,
        countQuery = """
            SELECT COUNT(p) FROM Photo p
            WHERE p.galleryId = :galleryId
              AND p.excludedAt IS NULL
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
              AND p.excludedAt IS NULL
              AND EXISTS (SELECT 1 FROM PhotoRating r WHERE r.photoId = p.id AND r.score >= :minScore)
        """,
        countQuery = """
            SELECT COUNT(p) FROM Photo p
            WHERE p.galleryId = :galleryId
              AND p.status = :status
              AND p.excludedAt IS NULL
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

    /**
     * 장수 한도에 드는 사진 수. PUT URL 이 죽었는데 아직 올라오지 않은 PENDING 은 뺀다 — 올라올 길이 없는 행이 자리를 차지하면
     * 실패한 업로드를 다시 올릴 때 한도에 막힌다. 그 행이 URL 을 다시 받으면 다시 센다([Photo.isUploadExpired]와 같은 조건).
     */
    @Query(
        """
            SELECT COUNT(p) FROM Photo p
            WHERE p.galleryId = :galleryId
              AND (p.status <> com.soma.wes.photo.domain.PhotoStatus.PENDING
                   OR p.uploadUrlExpiresAt IS NULL
                   OR p.uploadUrlExpiresAt >= :now)
        """,
    )
    fun countAgainstQuota(@Param("galleryId") galleryId: Long, @Param("now") now: Instant): Long
}
