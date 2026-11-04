package com.soma.wes.gallery.dto.request

import com.soma.wes.gallery.domain.GalleryWorkflowStatus
import io.swagger.v3.oas.annotations.media.Schema

data class ChangeWorkflowStatusRequest(
    @field:Schema(
        description = "바꿀 작업 진행 상태. 전이 순서 제한 없이 바로 바뀌며, ARCHIVED로 바꾸면 갤러리가 보관 단계로 넘어가 이후 수정이 막힌다.",
        example = "IN_PROGRESS",
    )
    val workflowStatus: GalleryWorkflowStatus,
)
