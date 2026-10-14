package com.soma.wes.folder.dto

/** AI 폴더 세트의 세부 폴더 하나와 지금 그 안에 배정된 사진들. 추천 도메인이 폴더 단위로 후보를 고르는 재료다. */
data class FolderSetDetailDto(
    val detailFolderId: Long,
    val conceptName: String,
    val detailName: String,
    val photoIds: List<Long>,
) {
    /** "컨셉 › 세부" — 이유 문장과 결과 요약에 쓰는 표시 이름. */
    val displayName: String
        get() = "$conceptName › $detailName"
}
