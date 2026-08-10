package com.soma.wes.collab.domain

import org.junit.jupiter.api.Test
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollabSessionTest {

    private val now: ZonedDateTime = ZonedDateTime.now()

    private fun session() = CollabSession(galleryId = 1L, shareToken = "first-token")

    @Test
    fun `열린 직후에는 폐기되지 않은 상태다`() {
        assertFalse(session().isRevoked)
    }

    @Test
    fun `폐기하면 그 시각이 남는다`() {
        val session = session()

        session.revoke(now)

        assertTrue(session.isRevoked)
        assertEquals(now, session.revokedAt)
    }

    @Test
    fun `두 번 폐기해도 처음 거둬들인 시각을 지킨다`() {
        val session = session()
        val firstRevokedAt = now.minusHours(3)

        session.revoke(firstRevokedAt)
        session.revoke(now)

        assertEquals(firstRevokedAt, session.revokedAt)
    }

    @Test
    fun `다시 열면 새 토큰이 나오고 폐기가 풀린다`() {
        // 같은 토큰을 되살리면 링크가 퍼진 그 단톡방이 함께 되살아난다.
        val session = session()
        session.revoke(now)

        session.reissueToken("second-token")

        assertEquals("second-token", session.shareToken)
        assertNull(session.revokedAt)
        assertFalse(session.isRevoked)
    }
}
