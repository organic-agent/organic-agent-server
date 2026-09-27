package com.soma.wes.category.dto.request

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

data class MoveCategoryPhotosRequest(
    val photoIds: List<Long>,
    /** null이면 논리적 미분류 상태로 옮긴다. */
    val targetDetailFolderId: Long? = null,
)
