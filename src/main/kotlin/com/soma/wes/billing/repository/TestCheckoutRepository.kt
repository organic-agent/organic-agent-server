package com.soma.wes.billing.repository

import com.soma.wes.billing.domain.TestCheckout
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock

interface TestCheckoutRepository : JpaRepository<TestCheckout, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockByIdAndUserId(id: String, userId: Long): TestCheckout?
    fun findByIdAndUserId(id: String, userId: Long): TestCheckout?
}
