package com.soma.wes.auth.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.LocalDate

/**
 * 심사용 로그인 링크(`/review#code=…`)의 설정. 값은 Parameter Store(`app.review-login.*`)가 덮어쓴다.
 *
 * 링크를 가진 사람은 누구나 [userId] 계정으로 들어온다 — 코드가 곧 비밀번호다. 그래서 기본은 꺼짐이고,
 * 심사 기간에만 켜고 [expiresOn]이 지나면 저절로 닫힌다.
 */
@ConfigurationProperties(prefix = "app.review-login")
data class ReviewLoginProperties(

    val enabled: Boolean = false,

    /** 링크의 비밀 코드. [MIN_CODE_LENGTH]보다 짧으면 켜져 있어도 꺼진 것으로 본다(추측 방지). */
    val code: String = "",

    /** 심사위원이 들어올 개인 사용자 계정 id. 팀이 심사용 소셜 계정으로 한 번 로그인해 만든다. */
    val userId: Long = 0,

    /** 이 날짜(KST)까지 열린다. null이면 날짜로는 닫지 않는다. */
    val expiresOn: LocalDate? = null,
) {

    companion object {
        const val MIN_CODE_LENGTH = 16
    }

    fun isOpenOn(today: LocalDate): Boolean =
        enabled && code.length >= MIN_CODE_LENGTH && userId > 0 && (expiresOn == null || !today.isAfter(expiresOn))

    /** 링크가 꺼져 있어도 계정은 심사용으로 남는다 — 탈퇴 막기는 켜기와 무관하게 id로만 본다. */
    fun isReviewAccount(id: Long): Boolean = userId > 0 && userId == id
}
