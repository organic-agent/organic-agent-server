package com.soma.wes.billing.domain

import java.time.ZonedDateTime

/** 이용 기간은 쿠폰 등록일이 아니라 갤러리 생성일부터 계산한다. 무료의 한 달은 달력 기준이다. */
enum class GalleryPlan(val planId: String, val displayName: String, val maxPhotoCount: Int) {
    FREE("free", "무료", 500),
    PRO("pro", "프로", 10_000),
    ;

    fun expiresAt(startedAt: ZonedDateTime): ZonedDateTime = when (this) {
        FREE -> startedAt.plusMonths(FREE_DURATION_MONTHS)
        PRO -> startedAt.plusDays(PRO_DURATION_DAYS)
    }

    companion object {
        /** 무료 갤러리의 생성일부터 한 달 이용 정책. */
        const val FREE_DURATION_MONTHS = 1L
        /** 프로 갤러리의 생성일부터 180일 이용 정책. */
        const val PRO_DURATION_DAYS = 180L
    }
}
