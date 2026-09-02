package com.soma.wes.workspace.repository

import com.soma.wes.workspace.domain.Workspace
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock

interface WorkspaceRepository : JpaRepository<Workspace, Long> {
    fun findByPersonalOwnerUserId(userId: Long): Workspace?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockById(id: Long): Workspace?
}
