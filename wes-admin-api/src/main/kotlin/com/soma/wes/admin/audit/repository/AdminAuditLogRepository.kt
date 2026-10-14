package com.soma.wes.admin.audit.repository

import com.soma.wes.admin.audit.domain.AdminAuditLog
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor

interface AdminAuditLogRepository :
    JpaRepository<AdminAuditLog, Long>,
    JpaSpecificationExecutor<AdminAuditLog>
