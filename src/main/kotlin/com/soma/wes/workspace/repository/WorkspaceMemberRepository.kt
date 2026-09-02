package com.soma.wes.workspace.repository

import com.soma.wes.workspace.domain.WorkspaceMember
import com.soma.wes.workspace.domain.WorkspaceRole
import org.springframework.data.jpa.repository.JpaRepository

interface WorkspaceMemberRepository : JpaRepository<WorkspaceMember, Long> {
    fun findAllByUserIdAndRoleIn(userId: Long, roles: Collection<WorkspaceRole>): List<WorkspaceMember>
    fun findAllByUserId(userId: Long): List<WorkspaceMember>
    fun findAllByWorkspaceId(workspaceId: Long): List<WorkspaceMember>
    fun findByWorkspaceIdAndUserId(workspaceId: Long, userId: Long): WorkspaceMember?
    fun existsByWorkspaceIdAndUserIdAndRoleIn(
        workspaceId: Long,
        userId: Long,
        roles: Collection<WorkspaceRole>,
    ): Boolean
}
