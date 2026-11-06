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

    fun findAllByGalleryIdAndIdIn(galleryId: Long, ids: Collection<Long>): List<CollabSession>

    fun existsByIdAndGalleryId(id: Long, galleryId: Long): Boolean


    /** 부부의 세션 수정(사진 추가·빼기, 이름, 링크 재발급·폐기, 댓글 삭제)이 잠근다 — 링크로 들어온 하객 반응 저장([findWithLockByCollabToken])과 같은 행으로 줄 세운다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockByIdAndGalleryId(id: Long, galleryId: Long): CollabSession?

    /** 하객의 댓글·좋아요 저장(`CollabSessionAccess.requireWritable`·`requireLikable`)이 잠근다 — 폐기·재발급·사진 빼기와 엇갈려 닫힌 링크나 빠진 사진에 반응이 남지 않게. */
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
