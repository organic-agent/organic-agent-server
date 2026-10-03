package com.soma.wes.analysis.dto

/** 운영자에게 보내는 알림 하나. 제목 한 줄과 본문 줄들이다 — 어느 채널로 어떻게 그릴지는 어댑터가 정한다. */
data class OpsAlertDto(
    val title: String,
    val lines: List<String>,
)
