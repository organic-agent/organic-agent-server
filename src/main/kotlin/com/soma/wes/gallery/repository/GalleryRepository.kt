package com.soma.wes.gallery.repository

import com.soma.wes.gallery.domain.Gallery
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock

interface GalleryRepository : JpaRepository<Gallery, Long> {

    fun findAllByStudioId(workspaceId: Long): List<Gallery>

    fun findAllByStudioIdIn(workspaceIds: Collection<Long>): List<Gallery>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockById(id: Long): Gallery?
}

fun GalleryRepository.requireById(id: Long): Gallery =
    findById(id)
        .orElseThrow { GalleryException(GalleryErrorCode.GALLERY_NOT_FOUND) }

fun GalleryRepository.requireWithLockById(id: Long): Gallery =
    findWithLockById(id)
        ?: throw GalleryException(GalleryErrorCode.GALLERY_NOT_FOUND)
