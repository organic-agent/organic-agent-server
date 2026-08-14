package com.soma.wes.studio.repository

import com.soma.wes.studio.domain.Studio
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock

interface StudioRepository : JpaRepository<Studio, Long> {

    fun findByUserId(userId: Long): Studio?

    fun existsByUserId(userId: Long): Boolean

    fun findByGalleryUrl(galleryUrl: String): Studio?

    fun existsByGalleryUrl(galleryUrl: String): Boolean

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockByUserId(userId: Long): Studio?
}
