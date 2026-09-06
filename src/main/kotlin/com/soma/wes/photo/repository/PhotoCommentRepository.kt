package com.soma.wes.photo.repository

import com.soma.wes.photo.domain.PhotoComment
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface PhotoCommentRepository : JpaRepository<PhotoComment, Long> {
    fun findAllByPhotoId(photoId: Long, pageable: Pageable): Page<PhotoComment>

    fun findByIdAndPhotoId(id: Long, photoId: Long): PhotoComment?
}
