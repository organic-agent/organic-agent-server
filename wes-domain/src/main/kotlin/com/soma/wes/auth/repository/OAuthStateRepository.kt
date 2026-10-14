package com.soma.wes.auth.repository

import com.soma.wes.auth.domain.OAuthState
import org.springframework.data.jpa.repository.JpaRepository
import java.time.ZonedDateTime

interface OAuthStateRepository : JpaRepository<OAuthState, String> {

    /**
     * 소비되지 않고 만료된 행을 걷어낸다. 로그인을 시작만 하고 그만두면 그 행은 아무도
     * 지우지 않으므로, 이걸 부르지 않으면 테이블이 단조 증가한다.
     */
    fun deleteAllByExpiresAtBefore(at: ZonedDateTime): Long
}
