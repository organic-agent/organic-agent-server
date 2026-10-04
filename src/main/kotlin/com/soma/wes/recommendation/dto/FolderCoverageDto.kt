package com.soma.wes.recommendation.dto

/** 폴더 하나의 연사 기준 추천 결과. [picks]는 추천 순서(점수 내림차순)대로의 분석 행 인덱스다. */
data class FolderCoverageDto(
    val picks: List<Int>,
    /** 큰 연사를 쪼갠 뒤의 연사 조각 수. 담은 조각도 센다. */
    val pieces: Int,
    /** 담은 사진이 있어 통째로 뺀 조각 수. */
    val skippedPieces: Int,
    /** 앞서 남긴 후보와 같은 컷으로 보여 거른 후보 수. */
    val nearDuplicates: Int,
)
