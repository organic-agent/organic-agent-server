package com.soma.wes.studio.domain

import com.soma.wes.workspace.domain.WorkspaceMember
import com.soma.wes.workspace.domain.WorkspaceRole

/** 이전 이름을 쓰는 관리자·테스트 호출부의 소스 호환 별칭. 저장 모델은 WORKSPACE_MEMBERS다. */
typealias StudioMember = WorkspaceMember
typealias StudioMemberRole = WorkspaceRole
