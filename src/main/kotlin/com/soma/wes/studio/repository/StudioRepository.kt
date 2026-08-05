package com.soma.wes.studio.repository

import com.soma.wes.studio.domain.Studio
import org.springframework.data.jpa.repository.JpaRepository

interface StudioRepository : JpaRepository<Studio, Long> {

    /** 작가 한 명이 스튜디오 하나를 갖는다(`UK(user_id)`). */
    fun findByUserId(userId: Long): Studio?

    fun existsByUserId(userId: Long): Boolean

    /** 공개 주소로 스튜디오를 찾는다. 온보딩의 주소 중복 확인에도 쓴다. */
    fun findByGalleryUrl(galleryUrl: String): Studio?

    fun existsByGalleryUrl(galleryUrl: String): Boolean
}
