package com.soma.wes.workspace.repository

import com.soma.wes.workspace.domain.WorkspaceMember
import com.soma.wes.workspace.domain.WorkspaceRole
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface WorkspaceMemberRepository : JpaRepository<WorkspaceMember, Long> {
    fun findAllByUserIdAndRoleIn(userId: Long, roles: Collection<WorkspaceRole>): List<WorkspaceMember>
    fun findAllByUserId(userId: Long): List<WorkspaceMember>
    fun findAllByWorkspaceId(workspaceId: Long): List<WorkspaceMember>
    fun findByWorkspaceIdAndUserId(workspaceId: Long, userId: Long): WorkspaceMember?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select member from WorkspaceMember member where member.workspaceId = :workspaceId order by member.id")
    fun findAllWithLockByWorkspaceId(@Param("workspaceId") workspaceId: Long): List<WorkspaceMember>
    fun existsByWorkspaceIdAndUserIdAndRoleIn(
        workspaceId: Long,
        userId: Long,
        roles: Collection<WorkspaceRole>,
    ): Boolean
}
