package com.soma.wes.collab.repository

import com.soma.wes.collab.domain.CollabPhotoLike
import com.soma.wes.collab.repository.projection.CollabLikeCountProjection
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface CollabPhotoLikeRepository : JpaRepository<CollabPhotoLike, Long> {

    /** 이 하객이 이 사진을 이미 눌렀는지. 눌렀으면 그대로 두고 없을 때만 만든다. */
    fun existsByCollabSessionIdAndPhotoIdAndParticipantId(
        collabSessionId: Long,
        photoId: Long,
        participantId: Long,
    ): Boolean

    /** 하객 자신이 어느 사진을 눌렀는지. 화면이 자기가 누른 버튼을 켜둔 채로 그린다. */
    fun findAllByCollabSessionIdAndPhotoIdInAndParticipantId(
        collabSessionId: Long,
        photoIds: Collection<Long>,
        participantId: Long,
    ): List<CollabPhotoLike>

    /**
     * 사진마다 좋아요가 몇 개씩인지. 아무도 누르지 않은 사진은 결과에 아예 없고,
     * 그 자리는 호출부가 0으로 채운다.
     */
    @Query(
        """
        SELECT l.photoId AS photoId, COUNT(l) AS count
        FROM CollabPhotoLike l
        WHERE l.collabSessionId = :sessionId AND l.photoId IN :photoIds
        GROUP BY l.photoId
        """,
    )
    fun countBySessionAndPhotoIdIn(
        @Param("sessionId") sessionId: Long,
        @Param("photoIds") photoIds: Collection<Long>,
    ): List<CollabLikeCountProjection>

    fun deleteAllByCollabSessionIdAndPhotoIdIn(collabSessionId: Long, photoIds: Collection<Long>)
    fun deleteAllByCollabSessionId(collabSessionId: Long)
}
