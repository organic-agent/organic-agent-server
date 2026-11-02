package com.soma.wes.selection.dto.response

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "선택·별점 변경 확인. 서버가 여러 대여도 같은 DB 변경 번호를 반환한다")
data class SelectionSyncResponse(
    @field:Schema(description = "이전 값과 다르면 선택 앨범 전체를 다시 읽는다. 증가 폭에 의미를 두지 않는다", example = "12")
    val selectionRevision: Long,

    @field:Schema(description = "이전 값과 다르면 별점 전체 목록으로 화면 상태를 교체한다. 삭제도 번호가 증가한다", example = "7")
    val ratingRevision: Long,
)
