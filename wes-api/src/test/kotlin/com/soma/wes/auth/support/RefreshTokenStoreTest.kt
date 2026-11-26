package com.soma.wes.auth.support

import com.soma.wes.auth.domain.RefreshToken
import com.soma.wes.auth.domain.RefreshTokenMatch
import com.soma.wes.auth.domain.Subject
import com.soma.wes.support.IntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate

@IntegrationTest
class RefreshTokenStoreTest @Autowired constructor(
    private val refreshTokenStore: RefreshTokenStore,
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
) {

    private val subject = Subject.from(42L)

    private fun match(token: RefreshToken, familyId: String? = FAMILY): RefreshTokenMatch =
        checkNotNull(transactionTemplate.execute { refreshTokenStore.matchForUpdate(subject, token, familyId) }) {
            "matchForUpdate는 null을 돌려주지 않는다"
        }

    @Test
    fun `토큰 문자열이 아니라 해시를 저장한다`() {
        // given
        val refreshToken = RefreshToken("refresh-token-value")

        // when
        refreshTokenStore.save(subject, refreshToken, FAMILY)

        // then
        // DB가 새도 저장된 값으로 재발급을 받을 수 없어야 한다.
        val stored = jdbcTemplate.queryForObject(
            "SELECT token_hash FROM refresh_tokens WHERE user_id = ?",
            String::class.java,
            subject.toUserId(),
        )
        assertThat(stored).isEqualTo(refreshToken.hashed()).isNotEqualTo(refreshToken.value)
    }

    @Test
    fun `앱의 해시는 V54 마이그레이션이 기존 토큰을 바꾼 식과 같다`() {
        // given
        // 실제 JWT처럼 점과 영숫자가 섞인 값
        val refreshToken = RefreshToken("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI0MiJ9.c2lnbmF0dXJl")

        // when
        val migrated = jdbcTemplate.queryForObject(
            "SELECT encode(sha256(convert_to(?, 'UTF8')), 'hex')",
            String::class.java,
            refreshToken.value,
        )

        // then
        // 다르면 배포 때 이미 로그인한 사용자 전원의 재발급이 실패한다.
        assertThat(refreshToken.hashed()).isEqualTo(migrated)
    }

    @Test
    fun `저장한 refresh token과 견주면 일치한다`() {
        // given
        val refreshToken = RefreshToken("refresh-token-value")
        refreshTokenStore.save(subject, refreshToken, FAMILY)

        // when & then
        assertThat(match(refreshToken)).isEqualTo(RefreshTokenMatch.MATCH)
    }

    @Test
    fun `다시 저장하면 이전 토큰은 일치하지 않는다`() {
        // given
        refreshTokenStore.save(subject, RefreshToken("old-token"), FAMILY)

        // when
        refreshTokenStore.save(subject, RefreshToken("new-token"), FAMILY)

        // then
        assertThat(match(RefreshToken("new-token"))).isEqualTo(RefreshTokenMatch.MATCH)
        // 방금 교체됐으므로 재사용이 아니라 탭 사이의 경쟁으로 본다.
        assertThat(match(RefreshToken("old-token"))).isEqualTo(RefreshTokenMatch.RACED)
    }

    @Test
    fun `삭제하면 저장된 토큰이 없다`() {
        // given
        refreshTokenStore.save(subject, RefreshToken("refresh-token-value"), FAMILY)

        // when
        refreshTokenStore.delete(subject)

        // then
        assertThat(match(RefreshToken("refresh-token-value"))).isEqualTo(RefreshTokenMatch.MISSING)
    }

    @Test
    fun `저장된 적 없는 사용자는 저장된 토큰이 없다`() {
        // when & then
        assertThat(match(RefreshToken("refresh-token-value"))).isEqualTo(RefreshTokenMatch.MISSING)
    }

    companion object {
        private const val FAMILY = "11111111-1111-1111-1111-111111111111"
    }
}
