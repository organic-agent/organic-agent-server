package com.soma.wes.recommendation.dto

import kotlin.math.round

internal data class SavedAiSelectionRoundDto(
    val jobId: Long,
    val galleryId: Long,
    val round: Int,
    val requestedCount: Int?,
    val scopeFolderIds: List<Long>?,
    /** 거절되지 않은 추천마다 추천 당시 폴더 이름. 잡 요약의 장수·폴더별 수를 다시 센다. */
    val folderOfPicks: List<String>,
    val summary: Map<String, Any?>,
)
