package com.soma.wes.user.domain

/**
 * 부부가 그리드에서 "담은 사람·매긴 사람"을 알아보는 색의 키.
 *
 * 키 목록과 실제 색상값(hex)은 웹이 정한다 — 서버는 어느 기기에서 봐도 같은 키가 나오도록 저장만 한다.
 * API에서는 웹의 소문자 키([key])로 주고받고, DB에는 enum 이름(대문자)으로 둔다. 값을 더하면
 * `users.ck_users_color` CHECK도 새 마이그레이션으로 함께 넓힌다.
 */
enum class UserColor(val key: String) {
    SKY("sky"),
    CORAL("coral"),
    APRICOT("apricot"),
    LEMON("lemon"),
    SAGE("sage"),
    MINT("mint"),
    ROSE("rose"),
    ;

    companion object {
        /** 웹 키와 정확히 같아야 한다(대소문자 포함). 모르는 키면 null. */
        fun fromKey(key: String): UserColor? = entries.firstOrNull { it.key == key }
    }
}
