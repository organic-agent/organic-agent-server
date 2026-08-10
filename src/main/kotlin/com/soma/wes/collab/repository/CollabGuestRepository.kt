package com.soma.wes.collab.repository

import com.soma.wes.collab.domain.CollabGuest
import org.springframework.data.jpa.repository.JpaRepository

interface CollabGuestRepository : JpaRepository<CollabGuest, Long> {

    /**
     * 토큰만으로 찾지 않고 세션까지 함께 본다.
     *
     * 한 하객이 여러 결혼식 링크를 받을 수 있는데, 토큰만 보면 A 갤러리에서 받은 토큰으로
     * B 갤러리에 글을 남길 수 있다. 세션이 다르면 없는 것으로 취급하는 편이 맞다.
     */
    fun findByGuestTokenAndCollabSessionId(guestToken: String, collabSessionId: Long): CollabGuest?

    fun findAllByIdIn(ids: Collection<Long>): List<CollabGuest>
}
