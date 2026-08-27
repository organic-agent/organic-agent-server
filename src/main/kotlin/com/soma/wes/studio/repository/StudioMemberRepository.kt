package com.soma.wes.studio.repository

import com.soma.wes.studio.domain.StudioMember
import com.soma.wes.studio.domain.StudioMemberRole
import org.springframework.data.jpa.repository.JpaRepository

interface StudioMemberRepository : JpaRepository<StudioMember, Long> {
    fun findAllByUserIdAndRoleIn(userId: Long, roles: Collection<StudioMemberRole>): List<StudioMember>

    fun existsByStudioIdAndUserIdAndRoleIn(
        studioId: Long,
        userId: Long,
        roles: Collection<StudioMemberRole>,
    ): Boolean
}
