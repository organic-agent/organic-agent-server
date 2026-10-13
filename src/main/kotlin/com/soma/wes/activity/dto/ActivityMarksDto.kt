package com.soma.wes.activity.dto

import java.time.ZonedDateTime

/** 메모장에서 꺼낸 활동 시각 묶음 — 갤러리·작업공간마다 가장 늦은 시각 하나. */
data class ActivityMarksDto(
    val galleries: Map<Long, ZonedDateTime>,
    val workspaces: Map<Long, ZonedDateTime>,
) {
    val isEmpty: Boolean get() = galleries.isEmpty() && workspaces.isEmpty()
}
