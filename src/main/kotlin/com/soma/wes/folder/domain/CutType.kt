package com.soma.wes.folder.domain

/**
 * 세부폴더의 컷 종류 — 인물 구성 기준(신부·신랑 단독 / 두 분 / 단체). 구도(바스트·풀샷)가 아니다.
 *
 * 폴더 사진들의 AI 분석 `subjects`를 다수결로 모아 과반일 때만 붙는다. 폴더 이름과는 독립된 표시 칩이다.
 * DB 컬럼(`detail_folders.category`)과 응답 필드(`category`)는 아직 옛 이름이다 — 웹과 함께 바꾼다.
 */
enum class CutType(val label: String) {
    BRIDE("신부"),
    GROOM("신랑"),
    COUPLE("두 분"),
    GROUP("단체"),
    ;

    companion object {

        /** AI가 쓰는 값(`unknown` 포함)을 그대로 받는다. 모르는 값은 null — 이 컬럼의 주인은 AI Lambda다. */
        fun fromSubjects(subjects: String?): CutType? = when (subjects?.lowercase()) {
            "bride" -> BRIDE
            "groom" -> GROOM
            "couple" -> COUPLE
            "group" -> GROUP
            else -> null
        }
    }
}
