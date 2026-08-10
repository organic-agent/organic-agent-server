package com.soma.wes.collab.repository

import com.soma.wes.collab.domain.CollabSession
import org.springframework.data.jpa.repository.JpaRepository

interface CollabSessionRepository : JpaRepository<CollabSession, Long> {

    /** 관리 화면의 링크 목록. 갤러리 하나에 여러 개라 최근에 만든 것이 위로 온다. */
    fun findAllByGalleryIdOrderByCreatedAtDesc(galleryId: Long): List<CollabSession>

    /**
     * 세션 하나를 다루는 모든 경로가 여기를 지난다.
     *
     * 세션 id만으로 찾지 않는 이유는 인가가 갤러리 단위이기 때문이다 — 자기 갤러리의 권한으로
     * 남의 갤러리 세션 id를 넣으면, id로만 찾을 경우 그대로 통과한다.
     */
    fun findByIdAndGalleryId(id: Long, galleryId: Long): CollabSession?

    fun countByGalleryId(galleryId: Long): Long

    fun findByShareToken(shareToken: String): CollabSession?
}
