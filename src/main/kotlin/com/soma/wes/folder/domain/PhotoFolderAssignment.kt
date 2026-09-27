package com.soma.wes.category.domain

import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.ZonedDateTime

@Entity
@Table(name = "photo_category_assignments")
class PhotoFolderAssignment(
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
    var assignedSource: CategorySource,

    @Column
    var confidence: Double? = null,

    @Column(name = "assigned_at", nullable = false)
    var assignedAt: ZonedDateTime,
) : BaseEntity() {
    fun moveTo(detailFolderId: Long, userId: Long, assignedAt: ZonedDateTime) {
        this.detailFolderId = detailFolderId
        assignedByUserId = userId
        assignedSource = CategorySource.USER
        confidence = null
        this.assignedAt = assignedAt
    }
}
