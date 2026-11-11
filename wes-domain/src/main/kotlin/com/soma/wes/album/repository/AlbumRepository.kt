package com.soma.wes.album.repository

import com.soma.wes.album.domain.Album
import org.springframework.data.jpa.repository.JpaRepository

interface AlbumRepository : JpaRepository<Album, Long> {
    fun findByGalleryId(galleryId: Long): Album?
}
