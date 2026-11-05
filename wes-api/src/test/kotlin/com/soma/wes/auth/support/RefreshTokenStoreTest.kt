package com.soma.wes.auth.support

import com.soma.wes.auth.domain.RefreshToken
import com.soma.wes.auth.domain.Subject
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

@IntegrationTest
class RefreshTokenStoreTest @Autowired constructor(
    private val refreshTokenStore: RefreshTokenStore,
) {

    private val subject = Subject.from(42L)

    @Test
    fun `저장한 refresh token을 다시 찾는다`() {
        // given
        val refreshToken = RefreshToken("refresh-token-value")

        // when
        refreshTokenStore.save(subject, refreshToken)

        // then
        assertThat(refreshTokenStore.find(subject)).isEqualTo(refreshToken)
    }

    @Test
    fun `다시 저장하면 이전 토큰은 무효가 된다`() {
        // given
        refreshTokenStore.save(subject, RefreshToken("old-token"))

        // when
        refreshTokenStore.save(subject, RefreshToken("new-token"))

        // then
        assertThat(refreshTokenStore.find(subject)).isEqualTo(RefreshToken("new-token"))
    }

    @Test
    fun `삭제하면 더 이상 찾을 수 없다`() {
        // given
        refreshTokenStore.save(subject, RefreshToken("refresh-token-value"))

        // when
        refreshTokenStore.delete(subject)

        // then
        assertThat(refreshTokenStore.find(subject)).isNull()
    }

    @Test
    fun `저장된 적 없는 사용자는 null을 반환한다`() {
        // when & then
        assertThat(refreshTokenStore.find(Subject.from(999L))).isNull()
    }
}
