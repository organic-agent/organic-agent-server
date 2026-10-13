package com.soma.wes.billing.domain

import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

/** 갤러리 영구 삭제 후에도 남겨 계정당 무료 이용을 한 번으로 제한한다. */
@Entity
@Table(name = "free_gallery_claims")
class FreeGalleryClaim private constructor(
    @Column(name = "user_id", nullable = false, updatable = false, unique = true)
    val userId: Long,
    @Column(name = "gallery_id", nullable = true, updatable = false)
    val galleryId: Long?,
) : BaseEntity() {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    companion object {
        fun of(userId: Long, galleryId: Long): FreeGalleryClaim =
            FreeGalleryClaim(userId = userId, galleryId = galleryId)
    }
}
