package com.soma.wes.studio.repository

import com.soma.wes.studio.domain.Studio
import org.springframework.data.jpa.repository.JpaRepository

interface StudioRepository : JpaRepository<Studio, Long> {

    fun findByUserId(userId: Long): Studio?

    fun existsByUserId(userId: Long): Boolean

    fun findByGalleryUrl(galleryUrl: String): Studio?

    fun existsByGalleryUrl(galleryUrl: String): Boolean
}
