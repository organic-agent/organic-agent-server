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
}
