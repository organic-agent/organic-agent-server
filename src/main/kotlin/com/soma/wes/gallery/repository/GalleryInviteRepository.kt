package com.soma.wes.gallery.repository

import com.soma.wes.gallery.domain.GalleryInvite
import org.springframework.data.jpa.repository.JpaRepository

interface GalleryInviteRepository : JpaRepository<GalleryInvite, Long> {

    fun findByToken(token: String): GalleryInvite?

    fun findAllByGalleryId(galleryId: Long): List<GalleryInvite>
}
