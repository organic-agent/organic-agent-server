package com.soma.wes.studio.dto.request

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class ExecuteStudioDeletionRequest(

    /** 운영자가 보고 확인한 현재 공개 주소. 경로의 숫자 id만 잘못 복사한 사고를 막는다. */
    @field:NotBlank
    @field:Size(max = 255)
    val confirmedGalleryUrl: String,

    /** 문의 티켓 번호와 최종 확인 근거를 포함한 내부 사유. */
    @field:NotBlank
    @field:Size(max = 1000)
    val reason: String,
)
