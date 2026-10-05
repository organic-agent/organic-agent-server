package com.soma.wes.folder.repository

import com.soma.wes.folder.domain.DetailFolderMerge
import jakarta.persistence.LockModeType
import java.time.ZonedDateTime
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface DetailFolderMergeRepository : JpaRepository<DetailFolderMerge, Long> {
    /** 되돌리기가 잠근다 — "실행 취소"를 두 번 눌러도 한 번만 되돌린다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findWithLockByIdAndGalleryId(id: Long, galleryId: Long): DetailFolderMerge?

    /**
     * 되돌릴 시간이 지난 합치기 기록을 지운다 — 숨은 원본을 지운 뒤라 주로 되돌린 기록이 남아 있다. 옮긴 사진 기록은 FK CASCADE로 함께 지워진다.
     * 숨은 원본은 먼저 [DetailFolderRepository.deleteHiddenByMergesBefore]로 지운다 — 기록을 먼저 지우면 원본을 찾을 길이 없다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "DELETE FROM detail_folder_merges WHERE merged_at < :cutoff", nativeQuery = true)
    fun deleteAllByMergedAtBefore(@Param("cutoff") cutoff: ZonedDateTime): Int
}
