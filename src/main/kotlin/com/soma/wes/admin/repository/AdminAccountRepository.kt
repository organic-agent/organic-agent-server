package com.soma.wes.admin.repository

import com.soma.wes.admin.domain.AdminAccount
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock

interface AdminAccountRepository : JpaRepository<AdminAccount, Long> {

    fun findByUsername(username: String): AdminAccount?

    fun existsByUsername(username: String): Boolean

    fun findAllByOrderByUsernameAsc(): List<AdminAccount>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockByUsername(username: String): AdminAccount?

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockById(id: Long): AdminAccount?
}
