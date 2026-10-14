package com.soma.wes.analysis.dto

/** AI 폴더 물질화의 결과 — 잡을 닫는 트랜잭션이 이것을 보고 DONE·FAILED와 알림 여부를 정한다. */
sealed interface MaterializeOutcomeDto {

    /** 폴더가 만들어졌다. 알림을 보낸다. */
    data class Created(
        val folders: Int,
        val details: Int,
        val assigned: Int,
    ) : MaterializeOutcomeDto

    /** 새로 넣을 사진이 없었다. 할 일이 없는 것이라 DONE이지만 알림은 없다. */
    data object NothingNew : MaterializeOutcomeDto

    /** 규칙 위반(갤러리 없음·배정 없음 등)으로 다시 돌려도 같다. FAILED. */
    data class Failed(val error: String) : MaterializeOutcomeDto
}
