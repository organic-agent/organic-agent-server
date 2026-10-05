package com.soma.wes.folder.repository

import com.soma.wes.folder.domain.DetailFolderMerge
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock

interface DetailFolderMergeRepository : JpaRepository<DetailFolderMerge, Long> {
    /** 되돌리기가 잠근다 — "실행 취소"를 두 번 눌러도 한 번만 되돌린다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockByIdAndGalleryId(id: Long, galleryId: Long): DetailFolderMerge?
}
