package com.soma.wes.collab.repository

import com.soma.wes.collab.domain.CollabParticipant
import com.soma.wes.collab.repository.projection.CollabParticipantCountProjection
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface CollabParticipantRepository : JpaRepository<CollabParticipant, Long> {
    fun findByGuestTokenAndCollabSessionId(guestToken: String, collabSessionId: Long): CollabParticipant?
    fun findByCollabSessionIdAndUserId(collabSessionId: Long, userId: Long): CollabParticipant?
    fun findAllByIdIn(ids: Collection<Long>): List<CollabParticipant>
    fun findAllByCollabSessionIdOrderByIdAsc(collabSessionId: Long): List<CollabParticipant>
    fun countByCollabSessionId(collabSessionId: Long): Long

    /** 공유폴더 목록이 세션마다 따로 세지 않도록 한 번에 센다. 참여자가 없는 세션은 결과에 없다. */
    @Query(
        "select p.collabSessionId as sessionId, count(p) as count from CollabParticipant p " +
            "where p.collabSessionId in :sessionIds group by p.collabSessionId",
    )
    fun countBySessionIds(sessionIds: Collection<Long>): List<CollabParticipantCountProjection>
}
