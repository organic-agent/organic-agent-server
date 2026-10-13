package com.soma.wes.auth.support

import com.soma.wes.auth.domain.OAuthState
import com.soma.wes.auth.repository.OAuthStateRepository
import com.soma.wes.global.config.TimeConfig
import com.soma.wes.support.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import java.time.Clock
import java.time.ZonedDateTime

@DataJpaTest
@Import(
    TestcontainersConfiguration::class,
    TimeConfig::class,
    OAuthStateStore::class,
)
class OAuthStateStoreTest @Autowired constructor(
    private val oAuthStateStore: OAuthStateStore,
    private val oAuthStateRepository: OAuthStateRepository,
    private val clock: Clock,
) {

    private val now: ZonedDateTime get() = ZonedDateTime.now(clock)

    @Nested
    @DisplayName("state를 발급할 때")
    inner class IssueState {

        @Test
        fun `state에는 초대 토큰이 실리지 않는다`() {
            // provider 로그와 리다이렉트 기록에 남는 값이다. 초대 토큰은 가진 사람이 곧 멤버가
            // 되는 자격증명이라 나가면 안 된다.
            // when
            val state = oAuthStateStore.issue("secret-invite-token")

            // then
            assertThat(state).doesNotContain("secret-invite-token")
        }

        @Test
        fun `초대 없는 일반 로그인도 state를 받는다`() {
            // 초대와 무관한 경로에도 같은 검증을 걸 수 있어야 CSRF 방어로 쓸 수 있다.
            // when
            val state = oAuthStateStore.issue(null)

            // then
            assertThat(oAuthStateRepository.existsById(state)).isTrue()
            assertThat(oAuthStateStore.consume(state)).isNull()
        }
    }

    @Nested
    @DisplayName("state를 소비할 때")
    inner class ConsumeState {

        @Test
        fun `발급한 state로 초대 토큰을 되찾는다`() {
            // given
            val state = oAuthStateStore.issue("invite-token")

            // when & then
            assertThat(oAuthStateStore.consume(state)).isEqualTo("invite-token")
        }

        @Test
        fun `한 번 쓴 state는 다시 쓸 수 없다`() {
            // 남겨두면 같은 state로 콜백을 두 번 태우는 재사용이 열린다.
            // given
            val state = oAuthStateStore.issue("invite-token")
            oAuthStateStore.consume(state)

            // when & then
            assertThat(oAuthStateStore.consume(state)).isNull()
            assertThat(oAuthStateRepository.existsById(state)).isFalse()
        }

        @Test
        fun `만료된 state는 초대를 돌려주지 않고 행도 지운다`() {
            // given
            val expired = oAuthStateRepository.save(
                OAuthState(state = "expired-state", inviteToken = "invite-token", expiresAt = now.minusMinutes(1)),
            )

            // when & then
            assertThat(oAuthStateStore.consume(expired.state)).isNull()
            assertThat(oAuthStateRepository.existsById(expired.state)).isFalse()
        }

        @Test
        fun `모르는 state와 빈 값은 그냥 없는 것으로 다룬다`() {
            // 로그인 자체를 막지는 않는다. 초대를 놓친 사용자는 링크를 다시 눌러 복구한다.
            // when & then
            assertSoftly { softly ->
                softly.assertThat(oAuthStateStore.consume("never-issued")).isNull()
                softly.assertThat(oAuthStateStore.consume(null)).isNull()
                softly.assertThat(oAuthStateStore.consume("")).isNull()
            }
        }
    }

    @Nested
    @DisplayName("만료된 행을 걷어낼 때")
    inner class PurgeExpired {

        @Test
        fun `소비되지 않고 만료된 행을 걷어낸다`() {
            // 동의 화면에서 창을 닫아버린 로그인은 콜백이 돌아오지 않아 아무도 지우지 않는다.
            // given
            oAuthStateRepository.save(
                OAuthState(state = "abandoned", inviteToken = null, expiresAt = now.minusMinutes(1)),
            )
            val alive = oAuthStateStore.issue(null)

            // when
            oAuthStateStore.purgeExpired()

            // then
            assertThat(oAuthStateRepository.existsById("abandoned")).isFalse()
            assertThat(oAuthStateRepository.existsById(alive)).isTrue()
        }
    }
}
