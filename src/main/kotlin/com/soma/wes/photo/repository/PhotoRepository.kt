package com.soma.wes.photo.repository

import com.soma.wes.photo.domain.Photo
import org.springframework.data.jpa.repository.JpaRepository

interface PhotoRepository : JpaRepository<Photo, Long> {

    fun findAllByGalleryIdOrderByDisplayOrderAsc(galleryId: Long): List<Photo>

    fun countByGalleryId(galleryId: Long): Long
}
