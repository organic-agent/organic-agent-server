package com.soma.wes.global

import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.support.IntegrationTest
import com.soma.wes.user.domain.User
import com.soma.wes.user.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.repository.findByIdOrNull
import org.springframework.transaction.support.TransactionTemplate

@IntegrationTest
class BaseEntityTest @Autowired constructor(
    private val userRepository: UserRepository,
    private val transactionTemplate: TransactionTemplate,
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
        userRepository.saveAndFlush(user)

        // then
        assertThat(user.createdAt).isNotNull()
        assertThat(user.updatedAt).isEqualTo(user.createdAt)
    }

    @Test
    fun `수정하면 updatedAt만 갱신된다`() {
        // given
        val saved = userRepository.saveAndFlush(newUser())
        val createdAt = checkNotNull(saved.createdAt)
        val firstUpdatedAt = checkNotNull(saved.updatedAt)

        // when
        transactionTemplate.executeWithoutResult {
            checkNotNull(userRepository.findByIdOrNull(saved.requiredId)).updateProfile(nickname = "수정된 이름", email = null)
        }

        // then
        val updated = checkNotNull(userRepository.findByIdOrNull(saved.requiredId))
        assertThat(updated.createdAt).isEqualTo(createdAt)
        assertThat(checkNotNull(updated.updatedAt)).isAfter(firstUpdatedAt)
    }
}
