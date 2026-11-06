package com.soma.wes.studio.repository

import com.soma.wes.studio.domain.StudioInvite
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock

interface StudioInviteRepository : JpaRepository<StudioInvite, Long> {
    fun findByToken(token: String): StudioInvite?
    /** 직원 초대 수락(`StudioInviteService.acceptIfPresent`)이 잠근다 — 폐기·만료를 확인하고 사용 횟수를 올리는 사이에 같은 초대의 다른 수락이 끼어 증가분을 덮지 않게. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockByToken(token: String): StudioInvite?
    fun findByWorkspaceIdAndRevokedAtIsNull(workspaceId: Long): StudioInvite?
}
