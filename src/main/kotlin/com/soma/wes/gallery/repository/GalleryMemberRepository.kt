package com.soma.wes.gallery.repository

import com.soma.wes.gallery.domain.GalleryMember
import org.springframework.data.jpa.repository.JpaRepository

interface GalleryMemberRepository : JpaRepository<GalleryMember, Long> {

    fun findByGalleryIdAndUserId(galleryId: Long, userId: Long): GalleryMember?

    fun existsByGalleryIdAndUserId(galleryId: Long, userId: Long): Boolean

    fun findAllByGalleryId(galleryId: Long): List<GalleryMember>

    /** 예비 부부 쪽 홈 화면: 내가 초대받은 갤러리 목록. */
    fun findAllByUserId(userId: Long): List<GalleryMember>
}
