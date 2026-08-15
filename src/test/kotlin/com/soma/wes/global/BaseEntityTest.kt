package com.soma.wes.global

import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.global.config.TimeConfig
import com.soma.wes.support.TestcontainersConfiguration
import com.soma.wes.user.domain.User
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import

@DataJpaTest
@Import(TestcontainersConfiguration::class, TimeConfig::class)
class BaseEntityTest @Autowired constructor(
    private val em: EntityManager,
) {

    private fun newUser() = User(
        provider = OAuthProvider.GOOGLE,
        providerId = "google-123",
        nickname = "테스터",
    )

    @Test
    fun `저장하면 createdAt과 updatedAt이 자동으로 채워진다`() {
        // given
        val user = newUser()
        assertThat(user.createdAt).isNull()

        // when
        em.persist(user)
        em.flush()

        // then
        assertThat(user.createdAt).isNotNull()
        assertThat(user.updatedAt).isEqualTo(user.createdAt)
    }

    @Test
    fun `수정하면 updatedAt만 갱신된다`() {
        // given
        val user = newUser()
        em.persist(user)
        em.flush()
        val createdAt = user.createdAt
        val firstUpdatedAt = user.updatedAt

        // when
        user.updateProfile(nickname = "수정된 이름", email = null)
        em.flush()

        // then
        assertThat(user.createdAt).isEqualTo(createdAt)
        assertThat(user.updatedAt!!).isAfter(firstUpdatedAt!!)
    }
}
