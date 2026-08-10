package com.soma.wes.collab.domain

import org.junit.jupiter.api.Test
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollabSessionTest {

    private val now: ZonedDateTime = ZonedDateTime.now()

    private fun session() = CollabSession.of(galleryId = 1L, name = "부모님께", shareToken = "first-token")

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

    @Test
    fun `이름의 앞뒤 공백은 떼고 받는다`() {
        assertEquals("부모님께", CollabSession.normalizeName("  부모님께  "))
    }

    @Test
    fun `공백뿐인 이름은 받지 않는다`() {
        // 화면에는 이름 없는 링크 두 개가 나란히 남고, 부부는 어느 쪽이 어느 쪽인지 알 수 없다.
        assertFailsWith<IllegalArgumentException> { CollabSession.normalizeName("   ") }
    }

    @Test
    fun `이름이 100자를 넘으면 받지 않는다`() {
        assertFailsWith<IllegalArgumentException> {
            CollabSession.normalizeName("가".repeat(CollabSession.MAX_NAME_LENGTH + 1))
        }
    }

    @Test
    fun `이름을 바꿔도 링크는 그대로다`() {
        // 하객이 들고 있는 주소가 이름 때문에 죽으면 안 된다.
        val session = session()

        session.rename("친구들에게")

        assertEquals("친구들에게", session.name)
        assertEquals("first-token", session.shareToken)
    }
}
