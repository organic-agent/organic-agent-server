package com.soma.wes.gallery.repository

import com.soma.wes.gallery.domain.GalleryMember
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import org.springframework.data.jpa.repository.JpaRepository

interface GalleryMemberRepository : JpaRepository<GalleryMember, Long> {

    fun findByGalleryIdAndUserId(galleryId: Long, userId: Long): GalleryMember?

    fun findAllByGalleryId(galleryId: Long): List<GalleryMember>

    fun findAllByUserId(userId: Long): List<GalleryMember>

    /** 정원([GalleryMember.MAX_PER_GALLERY]) 검사가 쓴다. 갤러리 행을 잠근 뒤에 세야 한다. */
    fun countByGalleryId(galleryId: Long): Long

    /**
     * 두 값을 함께 받는다. 인가는 갤러리 단위라, id만으로 찾으면 자기 갤러리 하나를 가진
     * 사람이 남의 갤러리 멤버를 집어낼 수 있다.
     */
    fun findByIdAndGalleryId(id: Long, galleryId: Long): GalleryMember?
}

fun GalleryMemberRepository.requireByIdAndGalleryId(id: Long, galleryId: Long): GalleryMember =
    findByIdAndGalleryId(id, galleryId)
        ?: throw GalleryException(GalleryErrorCode.MEMBER_NOT_FOUND)
