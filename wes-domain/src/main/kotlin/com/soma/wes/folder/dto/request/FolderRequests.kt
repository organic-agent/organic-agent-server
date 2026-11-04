package com.soma.wes.folder.dto.request

import com.soma.wes.folder.domain.FolderName
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class CreateConceptFolderRequest(
    @field:NotBlank
    @field:Size(max = FolderName.MAX_LENGTH)
    @field:Schema(
        description = "새 컨셉 폴더 이름. 앞뒤 공백을 지운 뒤 1~100자여야 하며, 같은 이름의 컨셉이 이미 있어도 새로 만든다.",
        example = "본식",
    )
    val name: String,
)

data class CreateDetailFolderRequest(
    @field:NotBlank
    @field:Size(max = FolderName.MAX_LENGTH)
    @field:Schema(
        description = "새 세부 폴더 이름. 앞뒤 공백을 지운 뒤 1~100자여야 한다. 사진 없는 빈 폴더로 생긴다.",
        example = "함께",
    )
    val name: String,
)

/** 컨셉 · 세부 폴더 이름 바꾸기. 앞뒤 공백은 지운다. */
data class RenameFolderRequest(
    @field:NotBlank
    @field:Size(max = FolderName.MAX_LENGTH)
    @field:Schema(
        description = "바꿀 이름. 앞뒤 공백을 지운 뒤 1~100자여야 한다. 사진 배정과 AI가 만든 출처는 그대로 남는다.",
        example = "야외 스냅",
    )
    val name: String,
)

data class MergeDetailFolderRequest(
    @field:Schema(
        description = "사진을 받을 세부 폴더 id. 같은 갤러리면 다른 컨셉 아래여도 되며, 합치는 원본 폴더 자신은 거절한다.",
        example = "12",
    )
    val targetDetailFolderId: Long,
)
