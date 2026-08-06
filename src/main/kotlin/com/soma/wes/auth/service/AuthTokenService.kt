package com.soma.wes.auth.service

import com.soma.wes.auth.domain.RefreshToken
import com.soma.wes.auth.dto.request.ReissueRequest
import com.soma.wes.auth.dto.response.ReissueResponse
import com.soma.wes.auth.exception.AuthErrorCode
import com.soma.wes.auth.exception.TokenException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AuthTokenService(
    private val authTokenProvider: AuthTokenProvider,
) {

    /**
     * refresh token으로 access token과 refresh token을 함께 재발급한다(rotation).
     *
     * 서명·만료가 유효하더라도 저장소에 기록된 토큰과 다르면 거부한다. 로그아웃했거나 다른 기기에서
     * 재로그인해 토큰이 갈린 경우가 그렇다.
     *
     * refresh token까지 교체하는 이유는, 탈취된 토큰의 수명을 "정상 사용자가 다음에 재발급할 때까지"로
     * 제한하기 위해서다. access token만 갱신하면 탈취된 refresh token은 만료일까지 계속 살아 있다.
     */
    @Transactional
    fun reissue(request: ReissueRequest): ReissueResponse {
        val requestedRefreshToken = RefreshToken(request.refreshToken)

        if (!authTokenProvider.isValidRefreshToken(requestedRefreshToken)) {
            throw TokenException(AuthErrorCode.REFRESH_TOKEN_INVALID)
        }

        val user = authTokenProvider.parseUser(requestedRefreshToken)

        // 새 refresh token이 저장소에 기록되면서 방금 쓴 토큰은 그 즉시 무효가 된다.
        return ReissueResponse.of(
            authTokenProvider.generateAccessToken(user),
            authTokenProvider.generateRefreshToken(user),
        )
    }
}
