package com.soma.wes.collab.repository.projection

interface CollabParticipantCountProjection {
    val sessionId: Long
    val count: Long
}
