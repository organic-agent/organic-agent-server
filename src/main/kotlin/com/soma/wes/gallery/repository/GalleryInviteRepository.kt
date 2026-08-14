package com.soma.wes.gallery.repository

import com.soma.wes.gallery.domain.GalleryInvite
import com.soma.wes.gallery.exception.GalleryErrorCode
import com.soma.wes.gallery.exception.GalleryException
import org.springframework.data.jpa.repository.JpaRepository

interface GalleryInviteRepository : JpaRepository<GalleryInvite, Long> {

    fun findByToken(token: String): GalleryInvite?

    /**
     * 두 값을 함께 받는다. 인가는 갤러리 단위라, id만으로 찾으면 자기 갤러리 하나를 가진
     * 작가가 남의 갤러리 초대를 폐기할 수 있다.
     */
    fun findByIdAndGalleryId(id: Long, galleryId: Long): GalleryInvite?

    /**
     * 갤러리의 현재 링크. `uk_gallery_invites_active_gallery_id`(V15)가 하나임을 보장한다.
     *
     * 만료된 것도 폐기되지 않았으면 여기 걸린다 — 작가 화면이 "만료됐으니 다시 발급하라"를
     * 보여줄 수 있어야 하고, 재발급은 그것까지 폐기하고 자리를 비운다.
     */
    fun findByGalleryIdAndRevokedAtIsNull(galleryId: Long): GalleryInvite?
}

fun GalleryInviteRepository.requireByIdAndGalleryId(id: Long, galleryId: Long): GalleryInvite =
    findByIdAndGalleryId(id, galleryId)
        ?: throw GalleryException(GalleryErrorCode.INVITE_NOT_FOUND)
