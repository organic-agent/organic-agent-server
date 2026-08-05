package com.soma.wes.gallery.repository

import com.soma.wes.gallery.domain.Gallery
import org.springframework.data.jpa.repository.JpaRepository

interface GalleryRepository : JpaRepository<Gallery, Long> {

    fun findAllByStudioId(studioId: Long): List<Gallery>
}
