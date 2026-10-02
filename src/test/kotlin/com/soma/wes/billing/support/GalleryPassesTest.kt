package com.soma.wes.billing.support

import com.soma.wes.billing.fixture.BillingFixture
import com.soma.wes.support.IntegrationTest
import com.soma.wes.user.fixture.UserFixture
import com.soma.wes.user.repository.UserRepository
import com.soma.wes.user.repository.requireWithLockById
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
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
    fun `오래 전에 등록한 프로 쿠폰도 사용일부터 달력 기준 1년을 제공한다`() {
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
        assertThat(checkNotNull(pass).expiresAt).isEqualTo(startedAt.plusYears(1))
    }

    @ParameterizedTest
    @CsvSource(
        "2026-04-01T12:00:00+09:00[Asia/Seoul], 2027-04-01T12:00:00+09:00[Asia/Seoul]",
        "2023-03-01T12:00:00+09:00[Asia/Seoul], 2024-03-01T12:00:00+09:00[Asia/Seoul]",
        "2024-02-29T12:00:00+09:00[Asia/Seoul], 2025-02-28T12:00:00+09:00[Asia/Seoul]",
        "2026-03-08T03:30:00-04:00[America/New_York], 2027-03-08T03:30:00-05:00[America/New_York]",
    )
    fun `프로 만료는 평년 윤년과 서머타임에서도 달력 1년과 시간대를 유지한다`(start: String, expiry: String) {
        // given
        val user = users.사용자()
        val startedAt = ZonedDateTime.parse(start)
        val expected = ZonedDateTime.parse(expiry)
        val coupon = billing.미사용_프로_쿠폰(userId = user.requiredId, registeredAt = startedAt.minusYears(1))

        // when
        val pass = checkNotNull(TransactionTemplate(transactions).execute {
            userRepository.requireWithLockById(user.requiredId)
            target.prepare(userId = user.requiredId, planId = "pro", couponId = coupon.requiredId, checkoutId = null, at = startedAt)
        })

        // then
        assertSoftly { softly ->
            softly.assertThat(pass.expiresAt).isEqualTo(expected)
            softly.assertThat(pass.expiresAt.toLocalDateTime()).isEqualTo(expected.toLocalDateTime())
            softly.assertThat(pass.expiresAt.zone).isEqualTo(startedAt.zone)
            softly.assertThat(pass.expiresAt.offset).isEqualTo(expected.offset)
            softly.assertThat(pass.maxPhotoCount).isEqualTo(10_000)
        }
    }
}
