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
