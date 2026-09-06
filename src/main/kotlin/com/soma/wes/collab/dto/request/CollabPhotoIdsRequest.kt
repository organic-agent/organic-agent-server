package com.soma.wes.collab.dto.request

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size

data class CollabPhotoIdsRequest(
    @field:Size(min = 1, max = MAX_BATCH_SIZE)
    @field:Schema(description = "같은 갤러리의 사진 id. 전체를 검증한 뒤 함께 반영한다. 중복 id는 한 번만 처리한다.")
    val photoIds: List<Long>,
) {
    companion object {
        /** 사진 직접 추가·제거 한 요청이 처리하는 최대 사진 수. */
        const val MAX_BATCH_SIZE = 200
    }
}
