package com.soma.wes.category.domain

/** AI 분석의 피사체 다수결로 붙는 세부폴더 분류 칩. 이름과는 독립된 표시 정보다. */
enum class DetailFolderCategory(val label: String) {
    BRIDE("신부"),
    GROOM("신랑"),
    COUPLE("두 분"),
    GROUP("단체"),
    ;

    companion object {
        fun fromSubjects(subjects: String?): DetailFolderCategory? = when (subjects?.lowercase()) {
            "bride" -> BRIDE
            "groom" -> GROOM
            "couple" -> COUPLE
            "group" -> GROUP
            else -> null
        }
    }
}
