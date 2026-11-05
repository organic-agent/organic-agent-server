package com.soma.wes.auth.repository

import com.soma.wes.auth.domain.RefreshTokenEntity
import org.springframework.data.jpa.repository.JpaRepository

interface RefreshTokenRepository : JpaRepository<RefreshTokenEntity, Long>
