package com.soma.wes.studio.repository

import com.soma.wes.studio.domain.Studio
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface StudioRepository : JpaRepository<Studio, Long> {

    fun findByUserId(userId: Long): Studio?

    fun existsByUserId(userId: Long): Boolean

    fun findByGalleryUrl(galleryUrl: String): Studio?

    fun existsByGalleryUrl(galleryUrl: String): Boolean

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockByUserId(userId: Long): Studio?

    /**
     * FK 자식 삽입의 KEY SHARE와도 충돌해야 하므로 Hibernate의 NO KEY UPDATE보다 강하게 잠근다.
     *
     * `@Lock(PESSIMISTIC_WRITE)`로 바꾸면 안 된다. PostgreSQLDialect가 그것을 NO KEY UPDATE로
     * 내보내는데, 그 락은 KEY SHARE와 충돌하지 않아 새 갤러리·사진이 스냅샷을 뜬 뒤에 끼어든다.
     * 그러면 DB에는 남고 S3에는 없는 행이 생기고, 컴파일도 테스트도 조용히 통과한다.
     */
    @Query(value = "select * from studios where id = :studioId for update", nativeQuery = true)
    fun findByIdForUpdate(@Param("studioId") studioId: Long): Studio?

    /** row lock으로 먼저 직렬화하지 못한 writer의 shared fence가 있으면 삭제 준비를 거절한다. */
    @Query(value = "select pg_try_advisory_xact_lock(:fenceKey)", nativeQuery = true)
    fun tryAcquireDeletionWriteFence(@Param("fenceKey") fenceKey: Long): Boolean

    /** app writer도 Lambda와 같은 studio fence protocol에 참여한다. */
    @Query(value = "select pg_try_advisory_xact_lock_shared(:fenceKey)", nativeQuery = true)
    fun tryAcquireSharedWriteFence(@Param("fenceKey") fenceKey: Long): Boolean
}
