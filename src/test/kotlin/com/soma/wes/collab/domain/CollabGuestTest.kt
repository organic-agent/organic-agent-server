package com.soma.wes.collab.domain

import com.soma.wes.collab.exception.CollabErrorCode
import com.soma.wes.collab.exception.CollabException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CollabGuestTest {

    @Test
    fun `앞뒤 공백은 떼고 저장한다`() {
        assertEquals("영희", CollabGuest.requireValidNickname("  영희  "))
    }

    @Test
    fun `공백뿐인 닉네임은 받지 않는다`() {
        // 화면에 이름표가 빈칸으로 붙으면 누가 쓴 글인지 알 수 없다.
        val exception = assertFailsWith<CollabException> {
            CollabGuest.requireValidNickname("   ")
        }

        assertEquals(CollabErrorCode.INVALID_NICKNAME, exception.errorCode)
    }

    @Test
    fun `너무 긴 닉네임은 받지 않는다`() {
        CollabGuest.requireValidNickname("가".repeat(CollabGuest.MAX_NICKNAME_LENGTH))

        assertFailsWith<CollabException> {
            CollabGuest.requireValidNickname("가".repeat(CollabGuest.MAX_NICKNAME_LENGTH + 1))
        }
    }
}
