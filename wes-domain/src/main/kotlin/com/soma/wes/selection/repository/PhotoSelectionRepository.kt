package com.soma.wes.selection.repository

import com.soma.wes.selection.domain.PhotoSelection
import org.springframework.data.jpa.repository.JpaRepository

interface PhotoSelectionRepository : JpaRepository<PhotoSelection, Long> {

    /**
     * 갤러리당 하나라 id가 아니라 갤러리로 찾는다. API 경로에도 앨범 id는 나오지 않는다.
     *
     * 잠금 메서드를 따로 두지 않는다 — 없는 행은 잠글 수 없어서, 앨범을 고치는 경로는
     * [com.soma.wes.gallery.repository.GalleryRepository.findWithLockById]로 갤러리 행을 먼저 잠근다.
     */
    fun findByGalleryId(galleryId: Long): PhotoSelection?
}
