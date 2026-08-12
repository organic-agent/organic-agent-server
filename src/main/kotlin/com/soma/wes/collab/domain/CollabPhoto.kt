package com.soma.wes.collab.domain

import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint


@Entity
@Table(
    name = "collab_photos",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_collab_photos_session_photo",
            columnNames = ["collab_session_id", "photo_id"],
        ),
    ],
    indexes = [
        Index(name = "idx_collab_photos_collab_session_id", columnList = "collab_session_id"),
    ],
)
class CollabPhoto(

    @Column(name = "collab_session_id", nullable = false, updatable = false)
    val collabSessionId: Long,

    @Column(name = "photo_id", nullable = false, updatable = false)
    val photoId: Long,

) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    val requiredId: Long
        get() = checkNotNull(id) { "저장되지 않은 협업 사진입니다." }
}
