package com.soma.wes.user.domain

import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.user.exception.UserErrorCode
import com.soma.wes.user.exception.UserException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UserTest {

    private fun newUser() = User(
        provider = OAuthProvider.KAKAO,
        providerId = "kakao-1",
        nickname = "테스터",
    )

    @Test
    fun `가입 직후에는 사용자 종류가 정해지지 않는다`() {
        val user = newUser()

        assertNull(user.userType)
        assertFalse(user.isOnboarded)
    }

    @Test
    fun `온보딩에서 사용자 종류를 정한다`() {
        val user = newUser()

        user.selectType(UserType.PHOTOGRAPHER)

        assertEquals(UserType.PHOTOGRAPHER, user.userType)
        assertTrue(user.isOnboarded)
    }

    @Test
    fun `이미 정해진 사용자 종류는 바꿀 수 없다`() {
        val user = newUser()
        user.selectType(UserType.CLIENT)

        val exception = assertFailsWith<UserException> { user.selectType(UserType.PHOTOGRAPHER) }

        assertEquals(UserErrorCode.USER_TYPE_ALREADY_SELECTED, exception.errorCode)
        assertEquals(UserType.CLIENT, user.userType)
    }
}
