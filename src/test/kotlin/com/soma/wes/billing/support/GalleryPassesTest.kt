package com.soma.wes.billing.support

import com.soma.wes.billing.fixture.BillingFixture
import com.soma.wes.support.IntegrationTest
import com.soma.wes.user.fixture.UserFixture
import com.soma.wes.user.repository.UserRepository
import com.soma.wes.user.repository.requireWithLockById
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.ZonedDateTime

@IntegrationTest
class GalleryPassesTest @Autowired constructor(
    private val target: GalleryPasses,
    private val users: UserFixture,
    private val userRepository: UserRepository,
    private val billing: BillingFixture,
    private val transactions: PlatformTransactionManager,
) {
    @Test
    fun `월말 생성한 무료 기간도 30일 고정이 아니라 달력 한 달이다`() {
        // given
        val user = users.사용자()
        val startedAt = ZonedDateTime.parse("2026-01-31T12:00:00+09:00[Asia/Seoul]")

        // when
        val pass = TransactionTemplate(transactions).execute {
            userRepository.requireWithLockById(user.requiredId)
            target.prepare(userId = user.requiredId, planId = "free", couponId = null, checkoutId = null, at = startedAt)
        }

        // then
        assertThat(checkNotNull(pass).expiresAt).isEqualTo(ZonedDateTime.parse("2026-02-28T12:00:00+09:00[Asia/Seoul]"))
    }

    @Test
    fun `오래 전에 등록한 프로 쿠폰도 사용일부터 180일을 제공한다`() {
        // given
        val user = users.사용자()
        val registeredAt = ZonedDateTime.parse("2025-01-01T12:00:00+09:00[Asia/Seoul]")
        val coupon = billing.미사용_프로_쿠폰(userId = user.requiredId, registeredAt = registeredAt)
        val startedAt = registeredAt.plusYears(1)

        // when
        val pass = TransactionTemplate(transactions).execute {
            userRepository.requireWithLockById(user.requiredId)
            target.prepare(userId = user.requiredId, planId = "pro", couponId = coupon.requiredId, checkoutId = null, at = startedAt)
        }

        // then
        assertThat(checkNotNull(pass).expiresAt).isEqualTo(startedAt.plusDays(180))
    }
}
