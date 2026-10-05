package com.soma.wes.folder.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class CreateConceptFolderRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val name: String,
)

data class CreateDetailFolderRequest(
    @field:NotBlank
    @field:Size(max = 100)
    val name: String,
)

data class MergeDetailFolderRequest(
    /** 사진을 받을 세부 폴더. 같은 갤러리면 다른 컨셉 아래여도 된다. */
    val targetDetailFolderId: Long,
)
