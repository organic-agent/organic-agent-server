package com.soma.wes.auth.support

import com.soma.wes.auth.domain.OAuthState
import com.soma.wes.auth.repository.OAuthStateRepository
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.Clock
import java.time.ZonedDateTime

/**
 * 스케줄러가 부르는 것과 같은 public 메서드([OAuthStateCleaner.purge])를 직접 불러,
 * 소비되지 않고 만료된 행만 걷어내는지 확인한다.
 */
@IntegrationTest
class OAuthStateCleanerTest @Autowired constructor(
    private val oAuthStateCleaner: OAuthStateCleaner,
    private val oAuthStateRepository: OAuthStateRepository,
    private val clock: Clock,
) {

    private val now: ZonedDateTime get() = ZonedDateTime.now(clock)

    @Test
    fun `만료된 행만 걷어내고 살아 있는 행은 남긴다`() {
        // given
        oAuthStateRepository.save(
            OAuthState(state = "abandoned", inviteToken = "invite-token", expiresAt = now.minusMinutes(1)),
        )
        oAuthStateRepository.save(
            OAuthState(state = "alive", inviteToken = null, expiresAt = now.plusMinutes(10)),
        )

        // when
        oAuthStateCleaner.purge()

        // then
        assertThat(oAuthStateRepository.existsById("abandoned")).isFalse()
        assertThat(oAuthStateRepository.existsById("alive")).isTrue()
    }

    @Test
    fun `걷어낼 것이 없으면 아무 행도 건드리지 않는다`() {
        // given
        oAuthStateRepository.save(
            OAuthState(state = "alive", inviteToken = null, expiresAt = now.plusMinutes(10)),
        )

        // when
        oAuthStateCleaner.purge()

        // then
        assertThat(oAuthStateRepository.count()).isEqualTo(1L)
    }
}
