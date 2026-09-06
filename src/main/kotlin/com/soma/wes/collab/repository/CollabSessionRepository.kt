package com.soma.wes.collab.repository

import com.soma.wes.collab.domain.CollabSession
import com.soma.wes.collab.exception.CollabErrorCode
import com.soma.wes.collab.exception.CollabException
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

interface CollabSessionRepository : JpaRepository<CollabSession, Long> {

    fun findAllByGalleryIdOrderByCreatedAtDesc(galleryId: Long): List<CollabSession>

    fun findByIdAndGalleryId(id: Long, galleryId: Long): CollabSession?

    fun existsByIdAndGalleryId(id: Long, galleryId: Long): Boolean

    fun findByConceptFolderId(conceptFolderId: Long): CollabSession?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockByIdAndGalleryId(id: Long, galleryId: Long): CollabSession?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockByCollabToken(collabToken: String): CollabSession?

    fun countByGalleryId(galleryId: Long): Long

    fun findByCollabToken(collabToken: String): CollabSession?

    /** 사진을 먼저 잠그기 위한 스코프 조회. 잠금 대기 전에 세션 엔티티를 캐시하지 않는다. */
    @Query("select s.galleryId from CollabSession s where s.collabToken = :collabToken")
    fun findGalleryIdByCollabToken(collabToken: String): Long?
}

fun CollabSessionRepository.requireByIdAndGalleryId(id: Long, galleryId: Long): CollabSession =
    findByIdAndGalleryId(id, galleryId)
        ?: throw CollabException(CollabErrorCode.SESSION_NOT_FOUND)
