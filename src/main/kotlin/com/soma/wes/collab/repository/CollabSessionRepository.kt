package com.soma.wes.collab.repository

import com.soma.wes.collab.domain.CollabSession
import com.soma.wes.collab.exception.CollabErrorCode
import com.soma.wes.collab.exception.CollabException
import org.springframework.data.jpa.repository.JpaRepository

interface CollabSessionRepository : JpaRepository<CollabSession, Long> {

    fun findAllByGalleryIdOrderByCreatedAtDesc(galleryId: Long): List<CollabSession>

    fun findByIdAndGalleryId(id: Long, galleryId: Long): CollabSession?

    fun countByGalleryId(galleryId: Long): Long

    fun findByCollabToken(collabToken: String): CollabSession?
}

fun CollabSessionRepository.requireByIdAndGalleryId(id: Long, galleryId: Long): CollabSession =
    findByIdAndGalleryId(id, galleryId)
        ?: throw CollabException(CollabErrorCode.SESSION_NOT_FOUND)
