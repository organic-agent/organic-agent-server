package com.soma.wes.recommendation.dto

data class MmrPickDto(
    /** 정렬된 분석 행의 인덱스 */
    val index: Int,
    /** 폴더 안 점수 순위(연사 dedup 후), 1부터 */
    val folderRank: Int,
    /** 그 폴더의 n_f */
    val quota: Int,
)
