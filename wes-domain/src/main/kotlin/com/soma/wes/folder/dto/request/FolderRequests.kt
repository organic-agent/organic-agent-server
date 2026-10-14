package com.soma.wes.folder.dto.request

import com.soma.wes.folder.domain.FolderName
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class CreateConceptFolderRequest(
    @field:NotBlank
    @field:Size(max = FolderName.MAX_LENGTH)
    val name: String,
)

data class CreateDetailFolderRequest(
    @field:NotBlank
    @field:Size(max = FolderName.MAX_LENGTH)
    val name: String,
)

/** 컨셉 · 세부 폴더 이름 바꾸기. 앞뒤 공백은 지운다. */
data class RenameFolderRequest(
    @field:NotBlank
    @field:Size(max = FolderName.MAX_LENGTH)
    val name: String,
)

data class MergeDetailFolderRequest(
    /** 사진을 받을 세부 폴더. 같은 갤러리면 다른 컨셉 아래여도 된다. */
    val targetDetailFolderId: Long,
)
