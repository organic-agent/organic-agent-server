package com.soma.wes.billing.repository

import com.soma.wes.billing.domain.FreeGalleryClaim
import org.springframework.data.jpa.repository.JpaRepository

interface FreeGalleryClaimRepository : JpaRepository<FreeGalleryClaim, Long> {
    fun existsByUserId(userId: Long): Boolean
}
