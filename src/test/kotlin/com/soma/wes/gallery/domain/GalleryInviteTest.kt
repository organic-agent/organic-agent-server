package com.soma.wes.gallery.domain

import com.soma.wes.global.config.TimeConfig
import org.junit.jupiter.api.Test
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GalleryInviteTest {

    private val now = ZonedDateTime.of(2026, 8, 5, 10, 0, 0, 0, TimeConfig.KST)

    private fun newInvite(expiresAt: ZonedDateTime = now.plusDays(7)) =
        GalleryInvite(galleryId = 1L, token = "token", expiresAt = expiresAt)

    @Test
    fun `발급 직후에는 쓸 수 있다`() {
        val invite = newInvite()

        assertTrue(invite.isUsableAt(now))
        assertFalse(invite.isRevoked)
    }

    @Test
    fun `만료 시각 당일까지는 쓸 수 있다`() {
        val invite = newInvite(expiresAt = now)

        assertTrue(invite.isUsableAt(now))
        assertFalse(invite.isUsableAt(now.plusNanos(1000)))
    }

    @Test
    fun `만료되면 쓸 수 없다`() {
        val invite = newInvite(expiresAt = now.minusDays(1))

        assertTrue(invite.isExpiredAt(now))
        assertFalse(invite.isUsableAt(now))
    }

    @Test
    fun `폐기하면 만료 전이라도 쓸 수 없다`() {
        val invite = newInvite(expiresAt = now.plusDays(7))

        invite.revoke(now)

        assertTrue(invite.isRevoked)
        assertFalse(invite.isExpiredAt(now))
        assertFalse(invite.isUsableAt(now))
    }

    @Test
    fun `두 번 폐기해도 처음 폐기한 시각을 유지한다`() {
        // 폐기 버튼을 두 번 눌렀다고 실패를 돌려줄 이유가 없다. 목적은 이미 이뤄졌다.
        val invite = newInvite()
        invite.revoke(now)

        invite.revoke(now.plusHours(1))

        assertEquals(now, invite.revokedAt)
    }

    @Test
    fun `기한을 연장하면 다시 쓸 수 있다`() {
        val invite = newInvite(expiresAt = now.minusDays(1))

        invite.extendExpiry(now.plusDays(7))

        assertTrue(invite.isUsableAt(now))
    }

    @Test
    fun `폐기된 링크는 기한을 연장해도 살아나지 않는다`() {
        val invite = newInvite(expiresAt = now.minusDays(1))
        invite.revoke(now)

        invite.extendExpiry(now.plusDays(7))

        assertFalse(invite.isUsableAt(now))
    }
}
