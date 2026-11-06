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
    fun findAllByWorkspaceIdIn(workspaceIds: Collection<Long>): List<WorkspaceMember>
    fun findByWorkspaceIdAndUserId(workspaceId: Long, userId: Long): WorkspaceMember?

    /** 마지막 OWNER를 지키는 쓰기(역할 변경·나가기·내보내기)가 잠근다 — 동시에 강등·탈퇴해 OWNER가 0명이 되지 않게 멤버 행을 id 순으로 잡는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select member from WorkspaceMember member where member.workspaceId = :workspaceId order by member.id")
    fun findAllWithLockByWorkspaceId(@Param("workspaceId") workspaceId: Long): List<WorkspaceMember>

    /** 탈퇴가 소유 스튜디오들의 OWNER 수를 한 번에 세며 잠근다. 강등·나가기와 같은 id 순이라 서로 엇갈려 기다리지 않는다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select member from WorkspaceMember member where member.workspaceId in :workspaceIds order by member.id")
    fun findAllWithLockByWorkspaceIdIn(@Param("workspaceIds") workspaceIds: Collection<Long>): List<WorkspaceMember>
    fun existsByWorkspaceIdAndUserIdAndRoleIn(
        workspaceId: Long,
        userId: Long,
        roles: Collection<WorkspaceRole>,
    ): Boolean
}
