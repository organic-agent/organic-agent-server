package com.soma.wes.folder.domain

import com.soma.wes.global.BaseEntity
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.Table
import java.time.Duration
import java.time.ZonedDateTime

/**
 * 세부 폴더 합치기 한 번의 기록. 웹의 "실행 취소"가 이것으로 합치기를 되돌린다.
 *
 * 합칠 때 원본 폴더는 지우지 않고 숨기므로([DetailFolder.hide]) 되돌리면 id · 순서 · 출처가 그대로 돌아온다.
 * 옮긴 사진은 [movedPhotos]가 옮기기 전 배정 정보와 함께 기억한다.
 */
@Entity
@Table(name = "detail_folder_merges")
class DetailFolderMerge(
    @Column(name = "gallery_id", nullable = false, updatable = false)
    val galleryId: Long,

    @Column(name = "source_detail_folder_id", nullable = false, updatable = false)
    val sourceDetailFolderId: Long,

    @Column(name = "target_detail_folder_id", nullable = false, updatable = false)
    val targetDetailFolderId: Long,

    @Column(name = "merged_by_user_id", updatable = false)
    val mergedByUserId: Long?,

    /** 옮긴 배정의 `assignedAt`도 이 값이다. 되돌릴 때 "그 사이 다시 옮겨졌나"를 이 값과 비교해 판단한다. */
    @Column(name = "merged_at", nullable = false, updatable = false)
    val mergedAt: ZonedDateTime,

    @ElementCollection
    @CollectionTable(name = "detail_folder_merge_photos", joinColumns = [JoinColumn(name = "merge_id")])
    val movedPhotos: List<MergedPhotoSnapshot>,
) : BaseEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @Column(name = "undone_at")
    var undoneAt: ZonedDateTime? = null
        protected set

    val requiredId: Long
        get() = checkNotNull(id) { "저장되지 않은 세부폴더 합치기입니다." }

    val isUndone: Boolean
        get() = undoneAt != null

    fun isUndoExpired(now: ZonedDateTime, window: Duration): Boolean = now.isAfter(mergedAt.plus(window))

    fun markUndone(at: ZonedDateTime) {
        undoneAt = at
    }
}
