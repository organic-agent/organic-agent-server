package com.soma.wes.admin.repository

import com.soma.wes.admin.domain.AdminAuthEvent
import org.springframework.data.jpa.repository.JpaRepository

interface AdminAuthEventRepository : JpaRepository<AdminAuthEvent, Long>
