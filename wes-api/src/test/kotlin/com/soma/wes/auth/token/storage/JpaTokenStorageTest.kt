package com.soma.wes.auth.token.storage

import com.soma.wes.auth.domain.RefreshToken
import com.soma.wes.auth.domain.Subject
import com.soma.wes.auth.token.config.JwtProperties
import com.soma.wes.global.config.TimeConfig
import com.soma.wes.support.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import

@DataJpaTest
@Import(TestcontainersConfiguration::class, JpaTokenStorage::class, TimeConfig::class)
@EnableConfigurationProperties(JwtProperties::class)
class JpaTokenStorageTest @Autowired constructor(
    private val tokenStorage: JpaTokenStorage,
) {

    private val subject = Subject.from(42L)

    @Test
    fun `저장한 refresh token을 다시 찾는다`() {
        // given
        val refreshToken = RefreshToken("refresh-token-value")

        // when
        tokenStorage.save(subject, refreshToken)

        // then
        assertThat(tokenStorage.find(subject)).isEqualTo(refreshToken)
    }

    @Test
    fun `다시 저장하면 이전 토큰은 무효가 된다`() {
        // given
        tokenStorage.save(subject, RefreshToken("old-token"))

        // when
        tokenStorage.save(subject, RefreshToken("new-token"))

        // then
        assertThat(tokenStorage.find(subject)).isEqualTo(RefreshToken("new-token"))
    }

    @Test
    fun `삭제하면 더 이상 찾을 수 없다`() {
        // given
        tokenStorage.save(subject, RefreshToken("refresh-token-value"))

        // when
        tokenStorage.delete(subject)

        // then
        assertThat(tokenStorage.find(subject)).isNull()
    }

    @Test
    fun `저장된 적 없는 사용자는 null을 반환한다`() {
        // when & then
        assertThat(tokenStorage.find(Subject.from(999L))).isNull()
    }
}
