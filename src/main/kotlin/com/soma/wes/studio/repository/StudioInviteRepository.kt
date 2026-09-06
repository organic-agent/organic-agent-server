package com.soma.wes.studio.repository

import com.soma.wes.studio.domain.StudioInvite
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock

interface StudioInviteRepository : JpaRepository<StudioInvite, Long> {
    fun findByToken(token: String): StudioInvite?
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockByToken(token: String): StudioInvite?
    fun findByWorkspaceIdAndRevokedAtIsNull(workspaceId: Long): StudioInvite?
}
