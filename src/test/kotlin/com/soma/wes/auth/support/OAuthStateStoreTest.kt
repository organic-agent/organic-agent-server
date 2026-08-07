package com.soma.wes.auth.support

import com.soma.wes.TestcontainersConfiguration
import com.soma.wes.auth.domain.OAuthState
import com.soma.wes.auth.repository.OAuthStateRepository
import com.soma.wes.global.config.TimeConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import java.time.Clock
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun `발급한 state로 초대 토큰을 되찾는다`() {
        val state = oAuthStateStore.issue("invite-token")

        assertEquals("invite-token", oAuthStateStore.consume(state))
    }

    @Test
    fun `state에는 초대 토큰이 실리지 않는다`() {
        // provider 로그와 리다이렉트 기록에 남는 값이다. 초대 토큰은 가진 사람이 곧 멤버가
        // 되는 자격증명이라 나가면 안 된다.
        val state = oAuthStateStore.issue("secret-invite-token")

        assertFalse(state.contains("secret-invite-token"))
    }

    @Test
    fun `한 번 쓴 state는 다시 쓸 수 없다`() {
        // 남겨두면 같은 state로 콜백을 두 번 태우는 재사용이 열린다.
        val state = oAuthStateStore.issue("invite-token")
        oAuthStateStore.consume(state)

        assertNull(oAuthStateStore.consume(state))
        assertFalse(oAuthStateRepository.existsById(state))
    }

    @Test
    fun `초대 없는 일반 로그인도 state를 받는다`() {
        // 초대와 무관한 경로에도 같은 검증을 걸 수 있어야 CSRF 방어로 쓸 수 있다.
        val state = oAuthStateStore.issue(null)

        assertTrue(oAuthStateRepository.existsById(state))
        assertNull(oAuthStateStore.consume(state))
    }

    @Test
    fun `만료된 state는 초대를 돌려주지 않고 행도 지운다`() {
        val expired = oAuthStateRepository.save(
            OAuthState(state = "expired-state", inviteToken = "invite-token", expiresAt = now.minusMinutes(1)),
        )

        assertNull(oAuthStateStore.consume(expired.state))
        assertFalse(oAuthStateRepository.existsById(expired.state))
    }

    @Test
    fun `모르는 state와 빈 값은 그냥 없는 것으로 다룬다`() {
        // 로그인 자체를 막지는 않는다. 초대를 놓친 사용자는 링크를 다시 눌러 복구한다.
        assertNull(oAuthStateStore.consume("never-issued"))
        assertNull(oAuthStateStore.consume(null))
        assertNull(oAuthStateStore.consume(""))
    }

    @Test
    fun `소비되지 않고 만료된 행을 걷어낸다`() {
        // 동의 화면에서 창을 닫아버린 로그인은 콜백이 돌아오지 않아 아무도 지우지 않는다.
        oAuthStateRepository.save(
            OAuthState(state = "abandoned", inviteToken = null, expiresAt = now.minusMinutes(1)),
        )
        val alive = oAuthStateStore.issue(null)

        oAuthStateStore.purgeExpired()

        assertFalse(oAuthStateRepository.existsById("abandoned"))
        assertTrue(oAuthStateRepository.existsById(alive))
    }
}
