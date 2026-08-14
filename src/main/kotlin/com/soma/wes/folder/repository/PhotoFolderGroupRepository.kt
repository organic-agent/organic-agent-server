package com.soma.wes.folder.repository

import com.soma.wes.folder.domain.PhotoFolderGroup
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock

interface PhotoFolderGroupRepository : JpaRepository<PhotoFolderGroup, Long> {

    fun findAllByGalleryIdOrderByCreatedAtDesc(galleryId: Long): List<PhotoFolderGroup>

    /**
     * id만으로 찾지 않는다. 갤러리를 함께 걸어야 남의 갤러리 부모폴더 id를 자기 갤러리 경로로
     * 넘겨 건드리는 요청이 막힌다 — 인가는 경로의 galleryId로만 확인하기 때문이다.
     */
    fun findByIdAndGalleryId(id: Long, galleryId: Long): PhotoFolderGroup?

    /**
     * 부모 안의 사진 구성을 바꾸는 동안 부모 행을 잠근다.
     *
     * "같은 부모 아래 사진 중복 금지"는 "이미 든 것 읽기 → 검사 → 저장"으로 지키는데, 같은
     * 부모에 두 요청이 동시에 들어오면 둘 다 검사를 통과해 유니크 제약에 걸린 한쪽이 통째로
     * 500으로 실패한다. 자식폴더 행은 이동의 출발지와 도착지가 달라 하나로 못 잠그므로,
     * 정책의 단위인 부모가 뮤텍스다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockByIdAndGalleryId(id: Long, galleryId: Long): PhotoFolderGroup?
}
