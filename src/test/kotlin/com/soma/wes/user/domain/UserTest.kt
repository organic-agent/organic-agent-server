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
    fun `같은 종류로 다시 정하는 것은 변경이 아니다`() {
        // 스튜디오 생성이 종류를 확정하는데, 중간에 실패해 "PHOTOGRAPHER인데 스튜디오는 없는"
        // 계정이 남을 수 있다. 여기서 막으면 그 계정은 영영 스튜디오를 만들지 못한다.
        val user = newUser()
        user.selectType(UserType.PHOTOGRAPHER)

        user.selectType(UserType.PHOTOGRAPHER)

        assertEquals(UserType.PHOTOGRAPHER, user.userType)
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
