package com.soma.wes.auth.repository

import com.soma.wes.auth.domain.RefreshTokenEntity
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock

interface RefreshTokenRepository : JpaRepository<RefreshTokenEntity, Long> {

    /**
     * 재발급이 견주기와 교체를 한 번에 하도록 행을 잠근다. 같은 토큰으로 동시에 온 두 번째 요청은 첫 요청의 교체를 본 뒤
     * 견주므로 [com.soma.wes.auth.domain.RefreshTokenMatch.RACED]로 거절된다(둘 다 재발급받아 한쪽 토큰이 버려지지 않는다).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockByUserId(userId: Long): RefreshTokenEntity?
}
