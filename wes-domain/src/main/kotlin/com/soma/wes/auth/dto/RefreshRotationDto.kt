package com.soma.wes.auth.dto

import com.soma.wes.auth.domain.AccessToken
import com.soma.wes.auth.domain.RefreshToken

/**
 * refresh 토큰 재발급의 결과([com.soma.wes.auth.service.AuthTokenProvider.rotateRefreshToken]).
 *
 * 거절을 예외 대신 값으로 돌려주는 이유: 재사용을 탐지하면 저장된 토큰을 지우고 거절한다. 그 삭제는 커밋돼야 하는데,
 * 트랜잭션 안에서 예외를 던지면 삭제까지 롤백된다. 그래서 트랜잭션은 값을 돌려주고 끝나고, 예외는 바깥의 서비스가 던진다.
 */
sealed interface RefreshRotationDto {

    data class Rotated(
        val accessToken: AccessToken,
        val refreshToken: RefreshToken,
    ) : RefreshRotationDto

    /** 받아 줄 수 없는 토큰이다. 클라이언트에는 이유를 가리지 않고 같은 401을 준다. */
    data object Rejected : RefreshRotationDto
}
