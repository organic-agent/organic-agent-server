package com.soma.wes.collab.support

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 하객 토큰 헤더 이름을 잠그는 회귀 가드. 상수 하나라 스프링 없이 돈다.
 */
class GuestTokenHeaderTest {

    @Test
    fun `하객 토큰 헤더 이름은 X-Guest-Token이다`() {
        // 이 값은 배포된 프론트와의 계약이다. 바꾸면 프론트가 보내는 헤더를 서버가 읽지 못해
        // 모든 하객이 GUEST_NOT_IDENTIFIED로 튕긴다 — 바꿔야 한다면 프론트와 함께 움직인다.
        // when & then
        assertThat(GuestTokenHeader.NAME).isEqualTo("X-Guest-Token")
    }
}
