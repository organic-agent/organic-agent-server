package com.soma.wes.folder.domain

import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.ZonedDateTime

// [GLOSSARY-1 2026-09-27] PhotoFolderAssignment → DetailFolderAssignment (용어집 D9: 사진은 세부 폴더에만 배정된다).
// [GLOSSARY-2 2026-09-27] 테이블 photo_category_assignments → detail_folder_assignments (V23)
@Entity
@Table(name = "detail_folder_assignments")
class DetailFolderAssignment(
    @Column(name = "gallery_id", nullable = false, updatable = false)
    val galleryId: Long,

    @Id
    @Column(name = "photo_id", updatable = false)
    val photoId: Long,

    @Column(name = "detail_folder_id", nullable = false)
    var detailFolderId: Long,

    @Column(name = "assigned_by_user_id")
    var assignedByUserId: Long? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "assigned_source", nullable = false, length = 20)
    var assignedSource: FolderSource,

    @Column
    var confidence: Double? = null,

    @Column(name = "assigned_at", nullable = false)
    var assignedAt: ZonedDateTime,

    /** 이 사진을 폴더에 처음 넣은 분석 잡. 사용자가 직접 넣었으면 null. 사용자가 옮겨도 남는다 — 물질화 재호출이 잡의 결과를 다시 찾는 표식이다. */
    @Column(name = "analysis_job_id", updatable = false)
    val analysisJobId: Long? = null,
) : BaseEntity() {
    fun moveTo(
        detailFolderId: Long,
        userId: Long,
        at: ZonedDateTime
    ) {
        this.detailFolderId = detailFolderId
        this.assignedByUserId = userId
        this.assignedSource = FolderSource.USER
        this.confidence = null
        this.assignedAt = at
    }

    fun snapshot() = MergedPhotoSnapshot(
        photoId = photoId,
        assignedByUserId = assignedByUserId,
        assignedSource = assignedSource,
        confidence = confidence,
        assignedAt = assignedAt,
    )

    /** 합치기를 되돌린다 — 옮기기 전 폴더와 배정 정보로 돌아간다. */
    fun restore(detailFolderId: Long, snapshot: MergedPhotoSnapshot) {
        this.detailFolderId = detailFolderId
        this.assignedByUserId = snapshot.assignedByUserId
        this.assignedSource = snapshot.assignedSource
        this.confidence = snapshot.confidence
        this.assignedAt = snapshot.assignedAt
    }
}
