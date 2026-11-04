package com.soma.wes.folder.dto.request

import io.swagger.v3.oas.annotations.media.Schema

data class MoveFolderPhotosRequest(
    @field:Schema(
        description = "옮길 사진 id 목록. 비어 있으면 거절하고 상한은 프로 요금제 최대 사진 수(10,000장)다. 중복은 하나로 치며, 하나라도 이 갤러리에 없거나 휴지통에 있으면 전체를 거절한다.",
    )
    val photoIds: List<Long>,
    @field:Schema(
        description = "옮겨 갈 세부 폴더 id. null이면 논리적 미분류 상태로 옮긴다. 사람이 옮긴 사진은 AI 폴더를 다시 만들어도 다시 배치되지 않는다.",
        example = "12",
    )
    val targetDetailFolderId: Long? = null,
)
