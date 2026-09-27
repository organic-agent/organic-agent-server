package com.soma.wes.category.controller.docs

import com.soma.wes.auth.domain.LoginUser
import com.soma.wes.category.dto.response.ConceptFolderResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity

@Tag(name = "[Category]")
interface FolderOrganizationControllerDocs {
    @Operation(summary = "분류 결과를 폴더로 1회 저장", description = "현재 Concept/Detail 구조를 확정한다. 저장 전에는 신규 갤러리의 사진을 선택할 수 없고 재저장은 409다.")
    fun save(loginUser: LoginUser, galleryId: Long): ResponseEntity<List<ConceptFolderResponse>>
}
