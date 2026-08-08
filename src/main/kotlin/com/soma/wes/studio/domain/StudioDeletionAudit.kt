package com.soma.wes.studio.domain

import com.soma.wes.global.BaseEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.util.UUID


/**
 * 완료된 스튜디오 삭제의 영구 감사 기록.
 *
 * 대상 스튜디오는 이미 사라졌으므로 연관관계나 외래키를 두지 않는다. [requestId]는 운영자가
 * 재시도할 때 같은 삭제를 다시 실행하지 않게 하는 멱등 키이기도 하다.
 */
@Entity
@Table(
    name = "studio_deletion_audits",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_studio_deletion_audits_request_id", columnNames = ["request_id"]),
    ],
    indexes = [
        Index(name = "idx_studio_deletion_audits_studio_id", columnList = "studio_id"),
        Index(name = "idx_studio_deletion_audits_operator_user_id", columnList = "operator_user_id"),
    ],
)
class StudioDeletionAudit(

    @Column(name = "request_id", nullable = false, updatable = false)
    val requestId: UUID,

    @Column(name = "studio_id", nullable = false, updatable = false)
    val studioId: Long,

    @Column(name = "studio_user_id", nullable = false, updatable = false)
    val studioUserId: Long,

    @Column(name = "operator_user_id", nullable = false, updatable = false)
    val operatorUserId: Long,

    @Column(name = "studio_gallery_url", nullable = false, updatable = false, length = 255)
    val studioGalleryUrl: String,

    @Column(nullable = false, updatable = false, length = 1000)
    val reason: String,

    @Column(name = "gallery_count", nullable = false, updatable = false)
    val galleryCount: Int,

    @Column(name = "photo_count", nullable = false, updatable = false)
    val photoCount: Int,

    @Column(name = "object_count", nullable = false, updatable = false)
    val objectCount: Int,

) : BaseEntity() {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    val requiredId: Long
        get() = id ?: error("아직 저장되지 않은 StudioDeletionAudit입니다.")
}
