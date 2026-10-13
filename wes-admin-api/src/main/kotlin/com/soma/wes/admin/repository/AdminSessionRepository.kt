package com.soma.wes.admin.repository

import com.soma.wes.admin.domain.AdminSession
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock

interface AdminSessionRepository : JpaRepository<AdminSession, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockByTokenHash(tokenHash: String): AdminSession?

    fun findAllByAdminIdAndRevokedAtIsNull(adminId: Long): List<AdminSession>
}
