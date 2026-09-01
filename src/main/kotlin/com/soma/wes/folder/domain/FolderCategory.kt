package com.soma.wes.folder.domain

/**
 * 자식폴더의 피사체 카테고리. 폴더 이름(컨셉)과 별개의 칩으로 노출된다.
 *
 * AI 폴더 생성이 폴더 안 사진들의 `photo_analysis.subjects`(CLIP zero-shot) 과반으로 정하고,
 * 이후에는 사용자가 PATCH로 고칠 수 있는 값이다.
 */
enum class FolderCategory(val label: String) {

    BRIDE("신부"),
    GROOM("신랑"),
    COUPLE("두 분"),
    GROUP("단체"),
    ;

    companion object {

        /**
         * `photo_analysis.subjects`의 값을 카테고리로 옮긴다. 어휘 계약은 AI repo가 지므로
         * 모르는 값은 예외가 아니라 null(카테고리 없음)이다 — 어휘가 바뀌어도 폴더 생성이 죽지 않는다.
         */
        fun fromSubjects(subjects: String?): FolderCategory? = when (subjects?.lowercase()) {
            "bride" -> BRIDE
            "groom" -> GROOM
            "couple" -> COUPLE
            "group" -> GROUP
            else -> null
        }
    }
}
