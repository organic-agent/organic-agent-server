package com.soma.wes.billing.repository

import com.soma.wes.billing.domain.TestCheckout
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock

interface TestCheckoutRepository : JpaRepository<TestCheckout, String> {
    /** 테스트 이용권으로 개인 갤러리를 여는 쓰기(`GalleryPasses.prepare`)가 잠근다 — 미사용 확인과 소비(consumedAt) 사이에 다른 개설이 같은 이용권을 쓰지 않게. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockByIdAndUserId(id: String, userId: Long): TestCheckout?
    fun findByIdAndUserId(id: String, userId: Long): TestCheckout?
}
