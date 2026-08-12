package com.soma.wes.collab.repository

import com.soma.wes.collab.domain.CollabPhoto
import com.soma.wes.collab.exception.CollabErrorCode
import com.soma.wes.collab.exception.CollabException
import com.soma.wes.collab.repository.projection.CollabSessionPhotoCountProjection
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface CollabPhotoRepository : JpaRepository<CollabPhoto, Long> {

    fun findAllByCollabSessionIdOrderByIdAsc(collabSessionId: Long, pageable: Pageable): Page<CollabPhoto>

    fun findAllByCollabSessionId(collabSessionId: Long): List<CollabPhoto>

    fun findByIdAndCollabSessionId(id: Long, collabSessionId: Long): CollabPhoto?

    fun countByCollabSessionId(collabSessionId: Long): Long

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockByIdAndCollabSessionId(id: Long, collabSessionId: Long): CollabPhoto?

    @Query(
        """
        SELECT cp.collabSessionId AS collabSessionId, COUNT(cp) AS count
        FROM CollabPhoto cp
        WHERE cp.collabSessionId IN :collabSessionIds
        GROUP BY cp.collabSessionId
        """,
    )
    fun countByCollabSessionIdIn(
        @Param("collabSessionIds") collabSessionIds: Collection<Long>,
    ): List<CollabSessionPhotoCountProjection>

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
        DELETE FROM CollabPhoto cp 
        WHERE cp.collabSessionId = :collabSessionId AND cp.photoId IN :photoIds
        """,
    )
    fun deleteAllByCollabSessionIdAndPhotoIdIn(
        @Param("collabSessionId") collabSessionId: Long,
        @Param("photoIds") photoIds: Collection<Long>,
    ): Int
}

fun CollabPhotoRepository.requireByIdAndCollabSessionId(id: Long, collabSessionId: Long): CollabPhoto =
    findByIdAndCollabSessionId(id, collabSessionId)
        ?: throw CollabException(CollabErrorCode.COLLAB_PHOTO_NOT_FOUND)

fun CollabPhotoRepository.requireWithLockByIdAndCollabSessionId(id: Long, collabSessionId: Long): CollabPhoto =
    findWithLockByIdAndCollabSessionId(id, collabSessionId)
        ?: throw CollabException(CollabErrorCode.COLLAB_PHOTO_NOT_FOUND)
