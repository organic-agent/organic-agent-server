package com.soma.wes.workspace.service

import com.soma.wes.user.domain.User
import com.soma.wes.workspace.domain.Workspace
import com.soma.wes.workspace.domain.WorkspaceMember
import com.soma.wes.workspace.domain.WorkspaceRole
import com.soma.wes.workspace.dto.response.WorkspaceResponse
import com.soma.wes.workspace.repository.WorkspaceMemberRepository
import com.soma.wes.workspace.repository.WorkspaceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class WorkspaceService(
    private val workspaceRepository: WorkspaceRepository,
    private val workspaceMemberRepository: WorkspaceMemberRepository,
) {
    @Transactional
    fun ensurePersonalWorkspace(user: User): Workspace {
        workspaceRepository.findByPersonalOwnerUserId(user.requiredId)?.let { return it }

        val workspace = workspaceRepository.save(
            Workspace.personal(user.requiredId, "${user.nickname}의 작업공간"),
        )
        workspaceMemberRepository.save(
            WorkspaceMember(
                workspaceId = workspace.requiredId,
                userId = user.requiredId,
                role = WorkspaceRole.OWNER,
            ),
        )
        return workspace
    }

    @Transactional(readOnly = true)
    fun listMine(userId: Long): List<WorkspaceResponse> {
        val memberships = workspaceMemberRepository.findAllByUserId(userId)
        val workspaces = workspaceRepository.findAllById(memberships.map { it.workspaceId })
            .associateBy { it.requiredId }

        return memberships.mapNotNull { membership ->
            workspaces[membership.workspaceId]?.let { WorkspaceResponse.of(it, membership.role) }
        }
    }
}
