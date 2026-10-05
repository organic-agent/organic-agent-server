package com.soma.wes.collab.dto.request

import com.soma.wes.billing.domain.GalleryPlan
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Size

data class CollabPhotoIdsRequest(
    @field:Size(min = 1)
    @field:Schema(description = "같은 갤러리의 사진 id. 갤러리 최대 사진 수까지 한 번에 보낼 수 있다. 전체를 검증한 뒤 함께 반영한다. 중복 id는 한 번만 처리한다.")
    val photoIds: List<Long>,
) {
    companion object {
        /**
         * 사진 직접 추가·제거 한 요청이 처리하는 최대 사진 수. 갤러리 최대 크기(프로 요금제)와 같게 둬서
         * 어떤 조합이든 요청 한 번에 들어가게 한다 — 나눠 보내다 끊기면 일부만 담긴 폴더가 남는다.
         */
        val MAX_BATCH_SIZE: Int = GalleryPlan.PRO.maxPhotoCount
    }
}
