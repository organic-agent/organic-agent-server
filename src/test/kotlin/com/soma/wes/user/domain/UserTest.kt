package com.soma.wes.user.domain

import com.soma.wes.auth.domain.OAuthProvider
import com.soma.wes.user.exception.UserErrorCode
import com.soma.wes.user.exception.UserException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

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
    }

    @Test
    fun `온보딩에서 사용자 종류를 정한다`() {
        val user = newUser()

        user.selectPhotographerType()

        assertEquals(UserType.PHOTOGRAPHER, user.userType)
    }

    @Test
    fun `같은 종류로 다시 정하는 것은 변경이 아니다`() {
        // 스튜디오 생성이 종류를 확정하는데, 중간에 실패해 "PHOTOGRAPHER인데 스튜디오는 없는"
        // 계정이 남을 수 있다. 여기서 막으면 그 계정은 영영 스튜디오를 만들지 못한다.
        val user = newUser()
        user.selectPhotographerType()

        user.selectPhotographerType()

        assertEquals(UserType.PHOTOGRAPHER, user.userType)
    }

    @Test
    fun `정해지지 않았을 때만 정하면 처음 한 번은 정해진다`() {
        val user = newUser()

        user.selectClientTypeIfUnset()

        assertEquals(UserType.CLIENT, user.userType)
    }

    @Test
    fun `정해지지 않았을 때만 정하면 이미 정해진 종류는 그대로 둔다`() {
        // 작가가 남의 갤러리에 초대받는 것은 정상 시나리오다. 여기서 던지면 수락 자체가 실패한다.
        val user = newUser()
        user.selectPhotographerType()

        user.selectClientTypeIfUnset()

        assertEquals(UserType.PHOTOGRAPHER, user.userType)
    }

    @Test
    fun `이미 정해진 사용자 종류는 바꿀 수 없다`() {
        val user = newUser()
        user.selectClientTypeIfUnset()

        val exception = assertFailsWith<UserException> { user.selectPhotographerType() }

        assertEquals(UserErrorCode.USER_TYPE_ALREADY_SELECTED, exception.errorCode)
        assertEquals(UserType.CLIENT, user.userType)
    }
}
