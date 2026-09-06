package com.soma.wes.collab.support

import com.soma.wes.collab.domain.CollabSession
import com.soma.wes.collab.repository.CollabSessionPhotoRepository
import org.springframework.stereotype.Component

/** 게스트 반응 인가와 모든 공유 화면이 같은 사진 집합을 사용한다. */
@Component
class CollabPhotoMembership(private val repository: CollabSessionPhotoRepository) {
    fun photoIds(session: CollabSession): List<Long> = photoIds(listOf(session))[session.requiredId].orEmpty()

    fun photoIds(sessions: Collection<CollabSession>): Map<Long, List<Long>> {
        if (sessions.isEmpty()) return emptyMap()
        return repository.findSharedPhotos(sessions.map { it.requiredId })
            .groupBy({ it.sessionId }, { it.photoId })
    }

    fun count(session: CollabSession): Long = photoIds(session).size.toLong()
    fun contains(session: CollabSession, photoId: Long): Boolean = photoId in photoIds(session)
}
