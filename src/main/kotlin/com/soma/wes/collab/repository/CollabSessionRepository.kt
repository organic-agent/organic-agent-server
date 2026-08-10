package com.soma.wes.collab.repository

import com.soma.wes.collab.domain.CollabSession
import org.springframework.data.jpa.repository.JpaRepository

interface CollabSessionRepository : JpaRepository<CollabSession, Long> {

    fun findByGalleryId(galleryId: Long): CollabSession?

    fun findByShareToken(shareToken: String): CollabSession?
}
