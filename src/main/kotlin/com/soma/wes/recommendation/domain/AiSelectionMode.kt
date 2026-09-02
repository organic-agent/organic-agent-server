package com.soma.wes.recommendation.domain

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter

/**
 * 추천 잡의 종류. DB에는 AI repo와의 계약대로 소문자(`draft`/`refine`)로 저장한다 —
 * 잡을 집은 워커가 이 문자열로 라운드(draft=1, refine=다음)를 분기한다.
 */
enum class AiSelectionMode(val dbValue: String) {

    /** 첫 라운드. 담긴 사진이 없어도 돈다. */
    DRAFT("draft"),

    /** 담기·거절 반응을 반영한 다음 라운드. 이전 라운드를 고치지 않고 새 라운드를 통째로 적는다. */
    REFINE("refine"),
    ;

    @Converter
    class DbConverter : AttributeConverter<AiSelectionMode, String> {

        override fun convertToDatabaseColumn(attribute: AiSelectionMode): String = attribute.dbValue

        override fun convertToEntityAttribute(dbData: String): AiSelectionMode =
            entries.first { it.dbValue == dbData }
    }
}
