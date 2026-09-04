package com.soma.wes.analysis.dto.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(
    description = "임베딩 실행 요청 결과. 계산이 끝났다는 뜻이 아니라 시작을 접수했다는 뜻이다.",
)
data class EmbeddingRunResponse(
    val galleryId: Long,
    @field:Schema(description = "이 요청이 만든 분석 잡(mode=EMBED). 상태는 GET /ai-analysis로도 볼 수 있다.")
    val jobId: Long,
    @field:Schema(
        description = "이번 실행이 채우려는 사진 수. 완료 여부는 GET /photos/summary의 embedded 수로 확인한다.",
    )
    val targets: Long,
)
