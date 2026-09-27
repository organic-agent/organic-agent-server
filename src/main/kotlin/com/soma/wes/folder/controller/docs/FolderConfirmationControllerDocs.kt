package com.soma.wes.folder.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.folder.dto.response.ConceptFolderResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

// [REFACTOR-RENAME 2026-09-27] FolderOrganization* → FolderConfirmation* (클래스 이름만 변경, 동작·REST 경로 동일)
@Tag(name = "[Folder]")
interface FolderConfirmationControllerDocs {
    // [REFACTOR-CONFIRM 2026-09-27] 메서드 save → confirm, @Operation 문구 "저장" → "확정". 폴더가 없을 때 AI 세트를 만드는 기존 동작을 설명에 드러냈다.
    @Operation(
        summary = "분류 결과로 폴더 구조를 1회 확정",
        description = "현재 컨셉/세부 폴더 구조를 확정하고 사진 셀렉 단계로 넘긴다. 폴더가 하나도 없으면 최신 분석 결과로 AI 폴더 세트를 먼저 만든다. " +
            "확정 전에는 신규 갤러리의 사진을 선택할 수 없고, 다시 확정하면 409다.",
    )
    fun confirm(loginUser: LoginUser, galleryId: Long): ResponseEntity<List<ConceptFolderResponse>>
}
