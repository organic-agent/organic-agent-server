package com.soma.wes.gallery.repository

import com.soma.wes.gallery.domain.GalleryInvite
import org.springframework.data.jpa.repository.JpaRepository

interface GalleryInviteRepository : JpaRepository<GalleryInvite, Long> {

    fun findByToken(token: String): GalleryInvite?

    /** 최근에 발급한 것이 위로. 작가가 방금 만든 링크를 목록 맨 위에서 찾게 한다. */
    fun findAllByGalleryIdOrderByIdDesc(galleryId: Long): List<GalleryInvite>
}
