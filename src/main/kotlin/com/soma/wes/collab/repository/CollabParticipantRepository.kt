package com.soma.wes.collab.repository

import com.soma.wes.collab.domain.CollabParticipant
import org.springframework.data.jpa.repository.JpaRepository

interface CollabParticipantRepository : JpaRepository<CollabParticipant, Long> {
    fun findByGuestTokenAndCollabSessionId(guestToken: String, collabSessionId: Long): CollabParticipant?
    fun findByCollabSessionIdAndUserId(collabSessionId: Long, userId: Long): CollabParticipant?
    fun findAllByIdIn(ids: Collection<Long>): List<CollabParticipant>
}
