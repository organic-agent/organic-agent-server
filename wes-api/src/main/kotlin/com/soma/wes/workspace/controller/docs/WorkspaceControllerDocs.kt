package com.soma.wes.workspace.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.workspace.dto.response.WorkspaceResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "[Workspace]", description = "작업공간 API")
interface WorkspaceControllerDocs {

    @Operation(
        summary = "내 작업공간 목록",
        description = "로그인한 사용자가 멤버로 속한 작업공간(개인·스튜디오)과 그 안의 역할을 돌려준다. 탈퇴한 멤버십과 삭제된 작업공간은 빠진다.",
    )
    fun listMine(loginUser: LoginUser): ResponseEntity<List<WorkspaceResponse>>
}
