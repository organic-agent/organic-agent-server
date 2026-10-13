package com.soma.wes.auth.token

import com.soma.wes.auth.domain.RefreshToken
import com.soma.wes.auth.domain.Subject

/**
 * 발급한 refresh token을 서버가 기억한다.
 *
 * JWT는 서명만 맞으면 유효하므로, 저장소 없이는 로그아웃이나 탈취 대응이 불가능하다.
 * 저장된 값과 대조해야 "우리가 마지막으로 발급한 토큰"만 재발급에 쓸 수 있다.
 */
interface TokenStorage {

    fun save(subject: Subject, refreshToken: RefreshToken): RefreshToken

    fun find(subject: Subject): RefreshToken?

    fun delete(subject: Subject)
}
