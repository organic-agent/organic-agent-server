package com.soma.wes.billing.repository

import com.soma.wes.billing.domain.ProCoupon
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock

interface ProCouponRepository : JpaRepository<ProCoupon, Long> {
    /** 코드 하나를 두 계정이 동시에 등록할 때 한 계정만 성공한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockByCodeHash(codeHash: String): ProCoupon?

    /** 같은 쿠폰으로 갤러리를 동시에 개설하지 못하게 사용 시점까지 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockByIdAndUserId(id: Long, userId: Long): ProCoupon?

    /** 관리자 상태 변경도 등록·소비와 동일한 쿠폰 행을 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockById(id: Long): ProCoupon?

    fun findAllByUserIdOrderByRegisteredAtDescIdDesc(userId: Long): List<ProCoupon>
}
