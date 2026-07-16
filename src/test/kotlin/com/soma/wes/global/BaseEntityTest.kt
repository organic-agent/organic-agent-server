package com.soma.wes.global

import com.soma.wes.TestcontainersConfiguration
import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.global.config.TimeConfig
import com.soma.wes.user.domain.User
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.context.annotation.Import
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
        val user = newUser()
        assertEquals(null, user.createdAt)

        em.persist(user)
        em.flush()

        assertNotNull(user.createdAt)
        assertEquals(user.createdAt, user.updatedAt)
    }

    @Test
    fun `수정하면 updatedAt만 갱신된다`() {
        val user = newUser()
        em.persist(user)
        em.flush()
        val createdAt = user.createdAt
        val firstUpdatedAt = user.updatedAt

        user.updateProfile(nickname = "수정된 이름", email = null)
        em.flush()

        assertEquals(createdAt, user.createdAt)
        assertTrue(user.updatedAt!! > firstUpdatedAt!!)
    }
}
