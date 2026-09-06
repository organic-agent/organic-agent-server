package com.soma.wes.collab.domain

import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint

/** 갤러리 id를 함께 저장해 세션과 사진이 같은 갤러리에 속한다는 규칙을 복합 FK로 지킨다. */
@Entity
@Table(name = "collab_session_photos", uniqueConstraints = [
    UniqueConstraint(name = "uk_collab_session_photos_session_photo", columnNames = ["collab_session_id", "photo_id"]),
])
class CollabSessionPhoto(
    @Column(name = "collab_session_id", nullable = false, updatable = false)
    val collabSessionId: Long,
    @Column(name = "gallery_id", nullable = false, updatable = false)
    val galleryId: Long,
    @Column(name = "photo_id", nullable = false, updatable = false)
    val photoId: Long,
) : BaseEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null
}
