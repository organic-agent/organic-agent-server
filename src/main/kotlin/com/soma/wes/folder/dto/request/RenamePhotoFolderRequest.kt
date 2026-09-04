package com.soma.wes.folder.dto.request

import com.soma.wes.folder.domain.FolderName
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@Schema(description = "폴더 이름 변경 요청")
data class RenamePhotoFolderRequest(
    @field:NotBlank
    @field:Size(max = FolderName.MAX_LENGTH)
    @field:Schema(description = "새 폴더 이름", example = "본식 - 신부 단독 (최종)")
    val name: String,
)
