package com.soma.wes.folder.repository

import com.soma.wes.folder.domain.PhotoFolder
import org.springframework.data.jpa.repository.JpaRepository

interface PhotoFolderRepository : JpaRepository<PhotoFolder, Long> {

    fun findAllByGalleryIdOrderByCreatedAtDesc(galleryId: Long): List<PhotoFolder>

    /**
     * id만으로 찾지 않는다. 갤러리를 함께 걸어야 남의 갤러리 폴더 id를 자기 갤러리 경로로
     * 넘겨 건드리는 요청이 막힌다 -- 인가는 경로의 galleryId로만 확인하기 때문이다.
     */
    fun findByIdAndGalleryId(id: Long, galleryId: Long): PhotoFolder?
}
