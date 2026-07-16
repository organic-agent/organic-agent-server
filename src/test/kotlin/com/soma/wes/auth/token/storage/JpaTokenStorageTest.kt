package com.soma.wes.auth.token.storage

import com.soma.wes.TestcontainersConfiguration
import com.soma.wes.auth.domain.RefreshToken
import com.soma.wes.auth.domain.Subject
import com.soma.wes.auth.token.config.JwtProperties
import com.soma.wes.global.config.TimeConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import kotlin.test.assertEquals
import kotlin.test.assertNull

@DataJpaTest
@Import(TestcontainersConfiguration::class, JpaTokenStorage::class, TimeConfig::class)
@EnableConfigurationProperties(JwtProperties::class)
class JpaTokenStorageTest @Autowired constructor(
    private val tokenStorage: JpaTokenStorage,
) {

    private val subject = Subject.from(42L)

    @Test
    fun `저장한 refresh token을 다시 찾는다`() {
        val refreshToken = RefreshToken("refresh-token-value")

        tokenStorage.save(subject, refreshToken)

        assertEquals(refreshToken, tokenStorage.find(subject))
    }

    @Test
    fun `다시 저장하면 이전 토큰은 무효가 된다`() {
        tokenStorage.save(subject, RefreshToken("old-token"))
        tokenStorage.save(subject, RefreshToken("new-token"))

        assertEquals(RefreshToken("new-token"), tokenStorage.find(subject))
    }

    @Test
    fun `삭제하면 더 이상 찾을 수 없다`() {
        tokenStorage.save(subject, RefreshToken("refresh-token-value"))

        tokenStorage.delete(subject)

        assertNull(tokenStorage.find(subject))
    }

    @Test
    fun `저장된 적 없는 사용자는 null을 반환한다`() {
        assertNull(tokenStorage.find(Subject.from(999L)))
    }
}
