package com.soma.wes.gallery.dto.request

import com.soma.wes.gallery.domain.ShootType
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "갤러리의 촬영 종류를 바꾸는 요청")
data class ChangeShootTypeRequest(

    @field:Schema(
        description = "REHEARSAL(리허설) | CEREMONY(본식) | OTHER. AI 폴더의 컨셉 목록이 이 값으로 갈리므로, " +
            "바꾼 뒤에는 AI 분석(NAMING)을 다시 돌려야 새 목록이 반영된다.",
        example = "REHEARSAL",
    )
    val shootType: ShootType,
)
