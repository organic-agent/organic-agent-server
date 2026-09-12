package com.soma.wes.retouch.dto.request

import io.swagger.v3.oas.annotations.media.Schema

data class RefineRetouchRequest(
    @field:Schema(description = "부부가 적은 요청 원문. 서버는 이 문장을 바꾸지 않는다.", example = "이거 지워줘")
    val text: String,

    @field:Schema(
        description = "요청이 달린 사진. 주면 서버가 미리보기를 읽어 모델에 함께 보낸다. 없으면 텍스트만 보고 말투만 정리한다.",
        example = "123",
    )
    val photoId: Long? = null,

    @field:Schema(description = "탭한 지점의 가로 비율(0~1). [y]와 함께 준다.", example = "0.41")
    val x: Double? = null,

    @field:Schema(description = "탭한 지점의 세로 비율(0~1). 좌표가 없으면 사진 전체에 대한 메모로 다룬다.", example = "0.5")
    val y: Double? = null,
)
