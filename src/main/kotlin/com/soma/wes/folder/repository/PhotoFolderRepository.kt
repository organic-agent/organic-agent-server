package com.soma.wes.folder.repository

import com.soma.wes.folder.domain.PhotoFolder
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock

interface PhotoFolderRepository : JpaRepository<PhotoFolder, Long> {

    fun findAllByGalleryIdOrderByCreatedAtDesc(galleryId: Long): List<PhotoFolder>

    /**
     * id만으로 찾지 않는다. 갤러리를 함께 걸어야 남의 갤러리 폴더 id를 자기 갤러리 경로로
     * 넘겨 건드리는 요청이 막힌다 -- 인가는 경로의 galleryId로만 확인하기 때문이다.
     */
    fun findByIdAndGalleryId(id: Long, galleryId: Long): PhotoFolder?

    /**
     * 사진을 담는 동안 폴더 행을 잠근다.
     *
     * 이미 든 사진을 걸러내려면 "읽고 -> 없는 것만 저장"을 하는데, 같은 폴더에 두 요청이
     * 동시에 들어오면 둘 다 없다고 판단해 같은 행을 저장한다. 유니크 제약이 막아주긴 하지만
     * 그때는 한쪽 요청이 통째로 500으로 실패한다 -- 신랑과 신부가 같은 사진을 동시에
     * 담으려 한 것뿐인데.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockByIdAndGalleryId(id: Long, galleryId: Long): PhotoFolder?
}
