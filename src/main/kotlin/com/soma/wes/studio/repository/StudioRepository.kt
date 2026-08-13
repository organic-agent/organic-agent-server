package com.soma.wes.studio.repository

import com.soma.wes.studio.domain.Studio
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface StudioRepository : JpaRepository<Studio, Long> {

    fun findByUserId(userId: Long): Studio?

    fun existsByUserId(userId: Long): Boolean

    fun findByGalleryUrl(galleryUrl: String): Studio?

    fun existsByGalleryUrl(galleryUrl: String): Boolean

    /** 하위 writer와 삭제 준비가 같은 스튜디오 행 앞에서 순서를 정하게 한다. */
    @Query(value = "select * from studios where user_id = :userId for update", nativeQuery = true)
    fun findByUserIdForUpdate(@Param("userId") userId: Long): Studio?

    /** FK 자식 삽입의 KEY SHARE와도 충돌해야 하므로 Hibernate의 NO KEY UPDATE보다 강하게 잠근다. */
    @Query(value = "select * from studios where id = :studioId for update", nativeQuery = true)
    fun findByIdForUpdate(@Param("studioId") studioId: Long): Studio?

    /** row lock으로 먼저 직렬화하지 못한 writer의 shared fence가 있으면 삭제 준비를 거절한다. */
    @Query(value = "select pg_try_advisory_xact_lock(:fenceKey)", nativeQuery = true)
    fun tryAcquireDeletionWriteFence(@Param("fenceKey") fenceKey: Long): Boolean

    /** app writer도 Lambda와 같은 studio fence protocol에 참여한다. */
    @Query(value = "select pg_try_advisory_xact_lock_shared(:fenceKey)", nativeQuery = true)
    fun tryAcquireSharedWriteFence(@Param("fenceKey") fenceKey: Long): Boolean
}
