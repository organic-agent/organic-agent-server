package com.soma.wes.admin.resource.dto.request

import com.soma.wes.admin.resource.domain.AdminReprocessScope
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size

data class AdminReprocessRequest(
    @field:PositiveOrZero
    @field:Schema(
        description = "조회한 갤러리의 version. 갤러리가 삭제됐거나 version이 다르면 409(ADMIN_409_5)이다.",
        example = "3",
    )
    val expectedVersion: Long,
    @field:NotBlank
    @field:Size(max = 128)
    @field:Schema(
        description = "재전송을 한 번으로 묶는 키. 영문·숫자·`._:-` 8~128자이며, 완료된 같은 요청은 accepted=false로 앞선 결과를 돌려주고 다른 요청에 다시 쓰면 409(ADMIN_409_7)이다.",
        example = "reprocess-20261104-001",
    )
    val idempotencyKey: String,
    @field:Schema(
        description = "재처리 범위. ALL은 갤러리의 분석 행을 전부 지우고 FAILED_ONLY는 분석이 실패한 사진만 되살린다(실패 사진이 없거나 categorize 중이면 거절). 생략하면 ALL — 이 필드가 생기기 전의 요청과 같은 동작이다.",
        example = "FAILED_ONLY",
    )
    val scope: AdminReprocessScope = AdminReprocessScope.ALL,
)
