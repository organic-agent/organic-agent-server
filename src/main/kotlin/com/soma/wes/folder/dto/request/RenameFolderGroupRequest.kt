package com.soma.wes.folder.dto.request

import com.soma.wes.folder.domain.FolderName
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@Schema(description = "부모폴더 이름 변경 요청")
data class RenameFolderGroupRequest(

    @field:NotBlank
    @field:Size(max = FolderName.MAX_LENGTH)
    @field:Schema(description = "새 부모폴더 이름", example = "본식 (최종)")
    val name: String,
)
