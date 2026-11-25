package com.soma.wes.auth.service

import com.soma.wes.auth.domain.RefreshToken
import com.soma.wes.auth.dto.RefreshRotationDto
import com.soma.wes.auth.dto.request.ReissueRequest
import com.soma.wes.auth.dto.response.ReissueResponse
import com.soma.wes.auth.exception.AuthErrorCode
import com.soma.wes.auth.exception.TokenException
import com.soma.wes.user.repository.UserRepository
import com.soma.wes.user.repository.requireById
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AuthTokenService(
    private val authTokenProvider: AuthTokenProvider,
    private val userRepository: UserRepository,
) {

    /**
     * refresh token으로 access token과 refresh token을 함께 재발급한다(rotation).
     *
     * 서명·만료가 유효하더라도 저장소에 기록된 토큰과 다르면 거부한다. 로그아웃했거나 다른 기기에서
     * 재로그인해 토큰이 갈린 경우가 그렇다. 같은 로그인에서 이미 교체된 토큰이 다시 오면 그 로그인까지 끊는다
     * ([AuthTokenProvider.rotateRefreshToken]).
     *
     * refresh token까지 교체하는 이유는, 탈취된 토큰의 수명을 "정상 사용자가 다음에 재발급할 때까지"로
     * 제한하기 위해서다. access token만 갱신하면 탈취된 refresh token은 만료일까지 계속 살아 있다.
     *
     * 트랜잭션 없이 돈다 — 재발급의 트랜잭션은 [AuthTokenProvider.rotateRefreshToken] 하나다. 거기서 끊은 로그인이
     * 커밋된 뒤에 이 메서드가 거절을 예외로 바꾼다. 같은 트랜잭션에서 던지면 끊은 결과까지 롤백된다.
     */
    fun reissue(request: ReissueRequest): ReissueResponse =
        when (val rotation = authTokenProvider.rotateRefreshToken(RefreshToken(request.refreshToken))) {
            is RefreshRotationDto.Rotated -> ReissueResponse.of(rotation.accessToken, rotation.refreshToken)
            RefreshRotationDto.Rejected -> throw TokenException(AuthErrorCode.REFRESH_TOKEN_INVALID)
        }

    @Transactional
    fun logout(userId: Long) {
        authTokenProvider.logout(userRepository.requireById(userId))
    }
}
